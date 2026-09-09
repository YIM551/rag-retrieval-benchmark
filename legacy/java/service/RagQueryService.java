package com.sleepwell.sleepwell_backend.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sleepwell.sleepwell_backend.rag.config.RagProperties;
import com.sleepwell.sleepwell_backend.rag.dto.RagQueryRequest;
import com.sleepwell.sleepwell_backend.rag.infra.impl.MultiQueryExpander;
import com.sleepwell.sleepwell_backend.rag.infra.PromptBuilder;
import com.sleepwell.sleepwell_backend.rag.infra.RagChatClient;
import com.sleepwell.sleepwell_backend.rag.infra.impl.GuardrailService;
import com.sleepwell.sleepwell_backend.rag.infra.impl.HybridRetriever;
import com.sleepwell.sleepwell_backend.rag.infra.port.DenseRetrieverPort;
import com.sleepwell.sleepwell_backend.rag.infra.port.RerankerPort;
import com.sleepwell.sleepwell_backend.rag.infra.port.SparseRetrieverPort;
import com.sleepwell.sleepwell_backend.rag.model.RagExecutionMode;
import com.sleepwell.sleepwell_backend.rag.model.RagTimingRecord;
import com.sleepwell.sleepwell_backend.rag.model.RetrievalOutcome;
import com.sleepwell.sleepwell_backend.rag.model.ScoredDoc;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@Service
public class RagQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum RagParallelStrategy {
        SEQUENTIAL,
        V1_OLD,
        V2_ASYNC,
        ASYNC;

        public RagParallelStrategy normalized() {
            return this == V2_ASYNC ? ASYNC : this;
        }
    }

    public enum ParallelGranularity {
        COARSE,
        FINE
    }

    // 간단 영/한 스톱워드(필요시 확장)
    private static final Set<String> STOP_EN = Set.of(
            "a", "an", "the", "and", "or", "for", "of", "to", "in", "on", "at", "by", "from", "with",
            "is", "are", "was", "were", "be", "been", "being", "as", "that", "this", "these", "those",
            "it", "its", "into", "about", "over", "under", "between", "among", "within", "without",
            "not", "no", "yes", "if", "then", "else", "than", "such", "also", "may", "might", "can", "could",
            "should", "would", "will", "do", "does", "did", "done", "have", "has", "had", "having",
            "we", "you", "they", "he", "she", "i", "me", "my", "our", "their", "his", "her", "them");

    private static final Set<String> STOP_KO = Set.of(
            "그리고", "또한", "또", "및", "등", "또는", "하지만", "그러나", "때문", "때문에", "대한", "대해",
            "에", "에서", "으로", "를", "을", "이", "가", "은", "는", "와", "과", "도", "만", "보다",
            "수", "등의", "등을", "있는", "없는", "하는", "했다", "합니다", "한다", "하여", "해서", "하며",
            "것", "등등", "혹은", "즉", "등지", "위해", "관련", "관련한", "관련하여", "중", "등에");

    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHangul}\\p{L}\\p{Nd}]+");
    private static final Pattern PCT_ENC_PATTERN = Pattern.compile("%[0-9a-fA-F]{2}");

    private final PromptBuilder prompt;
    private final RagChatClient chat;
    private final HybridRetriever hybrid;
    private final DenseRetrieverPort denseRetriever;
    private final SparseRetrieverPort sparseRetriever;
    private final MultiQueryExpander expander;
    private final Optional<RerankerPort> reranker; // 옵션
    private final Optional<GuardrailService> guard; // 옵션
    private final ExecutorService ragExecutor;
    private final RagExecutionMode executionMode;
    private final ParallelGranularity parallelGranularity;
    private final int ragExecutorThreads;
    private final boolean nestedDenseSparse;

    @Value("${rag.retrieval.dense.topKInit:60}")
    private int denseTopK;
    @Value("${rag.retrieval.sparse.topKInit:60}")
    private int sparseTopK;
    @Value("${rag.retrieval.mmr.enabled:true}")
    private boolean useMmr;
    @Value("${rag.retrieval.mmr.k:25}")
    private int mmrK;
    @Value("${rag.retrieval.mmr.lambda:0.45}")
    private double mmrLambda;
    @Value("${rag.retrieval.rerank.enabled:true}")
    private boolean useRerank;
    @Value("${rag.retrieval.rerank.topK:20}")
    private int rerankTopK;

    public RagQueryService(
            PromptBuilder prompt,
            RagChatClient chat,
            HybridRetriever hybrid,
            DenseRetrieverPort denseRetriever,
            SparseRetrieverPort sparseRetriever,
            MultiQueryExpander expander,
            Optional<RerankerPort> reranker,
            Optional<GuardrailService> guard,
            RagProperties ragProperties,
            @Value("${rag.parallel.granularity:${RAG_PARALLEL_GRANULARITY:FINE}}") String granularityProperty,
            @Value("${rag.retrieval.executorThreads:${RAG_RETRIEVAL_EXECUTOR_THREADS:8}}") int ragExecutorThreads,
            @Value("${rag.retrieval.nestedDenseSparse:true}") boolean nestedDenseSparse) {
        this.prompt = prompt;
        this.chat = chat;
        this.hybrid = hybrid;
        this.denseRetriever = denseRetriever;
        this.sparseRetriever = sparseRetriever;
        this.expander = expander;
        this.reranker = reranker;
        this.guard = guard;
        this.executionMode = ragProperties.getExecutionMode();
        this.parallelGranularity = parseParallelGranularity(granularityProperty);
        this.ragExecutorThreads = ragExecutorThreads;
        this.nestedDenseSparse = nestedDenseSparse;
        this.ragExecutor = Executors.newFixedThreadPool(this.ragExecutorThreads);
        log.info("[RAG] RagQueryService initialized: executionMode={}, granularity={}, threadPoolSize={}, nestedDenseSparse={}",
                this.executionMode,
                this.parallelGranularity,
                this.ragExecutorThreads,
                this.nestedDenseSparse);
    }

    @PreDestroy
    public void shutdownExecutor() {
        ragExecutor.shutdown();
    }

    private ParallelGranularity parseParallelGranularity(String value) {
        if (value == null || value.isBlank()) {
            return ParallelGranularity.FINE;
        }
        try {
            return ParallelGranularity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("[RAG] Unknown parallel granularity '{}', fallback to FINE", value);
            return ParallelGranularity.FINE;
        }
    }

    /** 동기 응답: 간단보기/자세히보기로 분리 */
    public Map<String, Object> answer(RagQueryRequest req) throws Exception {
        long totalStart = System.nanoTime();
        RagTimingCollector timings = new RagTimingCollector();
        RagParallelStrategy selectedStrategy = mapStrategy(executionMode);
        try {
            log.debug("[RAG][EXEC] mode={}, parallelStrategy={}, granularity={}, nestedDenseSparse={}",
                    executionMode,
                    selectedStrategy,
                    parallelGranularity,
                    nestedDenseSparse);
            log.debug("Handling RAG query in mode={}", executionMode);
            if (guard.isPresent()) {
                long guardStart = System.nanoTime();
                boolean hasRedFlag = guard.get().hasRedFlag(req.query());
                timings.addGuardrailMs(toMs(guardStart, System.nanoTime()));
                if (hasRedFlag) {
                    String msg = "⚠️ 위급 징후 가능성이 있는 표현이 포함되어 있습니다. 즉시 전문의 상담 또는 응급실 방문을 권고합니다.";
                    return Map.of(
                            "message", "### 간단보기\n" + msg + "\n\n### 자세히보기\n" + msg,
                            "message_compact", msg,
                            "message_detailed", msg,
                            "citations", List.of());
                }
            }

            return switch (executionMode) {
                case SEQUENTIAL -> executeSequential(req, timings, totalStart);
                case PARALLEL_OLD -> executeParallelOld(req, timings, totalStart);
                case PARALLEL_SYNC -> executeParallelSync(req, timings, totalStart);
                default -> {
                    log.warn("[RAG] Unknown execution mode '{}', falling back to sequential", executionMode);
                    yield executeSequential(req, timings, totalStart);
                }
            };
        } finally {
            long totalEnd = System.nanoTime();
            timings.setTotalMs(toMs(totalStart, totalEnd));
            logTimingMetric(timings.toRecord(
                    executionMode.name(),
                    selectedStrategy.normalized().name(),
                    ragExecutorThreads,
                    parallelGranularity,
                    nestedDenseSparse));
        }
    }

    private Map<String, Object> executeSequential(RagQueryRequest req, RagTimingCollector timings, long totalStart) throws Exception {
        return runPipeline(req, timings, RagParallelStrategy.SEQUENTIAL, totalStart);
    }

    private Map<String, Object> executeParallelOld(RagQueryRequest req, RagTimingCollector timings, long totalStart) throws Exception {
        return runPipeline(req, timings, RagParallelStrategy.V1_OLD, totalStart);
    }

    private Map<String, Object> executeParallelSync(RagQueryRequest req, RagTimingCollector timings, long totalStart) throws Exception {
        return runPipeline(req, timings, RagParallelStrategy.ASYNC, totalStart);
    }

    private RagParallelStrategy mapStrategy(RagExecutionMode mode) {
        return switch (mode) {
            case SEQUENTIAL -> RagParallelStrategy.SEQUENTIAL;
            case PARALLEL_OLD -> RagParallelStrategy.V1_OLD;
            case PARALLEL_SYNC -> RagParallelStrategy.ASYNC;
        };
    }

    private Map<String, Object> runPipeline(RagQueryRequest req, RagTimingCollector timings, RagParallelStrategy strategy, long totalStart) throws Exception {
        QueryExpansionResult expansionResult = expandQuery(req, timings);

        long retrievalStart = System.nanoTime();
        var retrieval = retrieveWithFilters(expansionResult, timings, strategy);
        timings.setRetrievalMs(toMs(retrievalStart, System.nanoTime()));
        var ctx = retrieval.getCitations();

        long contextStart = System.nanoTime();
        var built = prompt.build(expansionResult.query(), ctx, profileMap(req));
        timings.setContextMs(toMs(contextStart, System.nanoTime()));

        long llmStart = System.nanoTime();
        String detailed = chat.chat(built.system(), built.user());
        long llmEnd = System.nanoTime();
        timings.setLlmMs(toMs(llmStart, llmEnd));

        String compact = extractFirstSentences(detailed, 2);

        Map<String, Object> response = Map.of(
                "message", renderCombined(compact, detailed),
                "message_compact", nullToEmpty(compact),
                "message_detailed", nullToEmpty(detailed),
                "citations", ctx);

        long endNanos = System.nanoTime();
        long latencyMs = toMs(totalStart, endNanos);
        log.info(
                "RAG_QUERY_METRIC,mode=hybrid,execution={},latency_ms={},dense_hits={},sparse_hits={},total_hits={}",
                executionMode,
                latencyMs,
                retrieval.denseHits(),
                retrieval.sparseHits(),
                retrieval.totalHits());

        return response;
    }

    // -------------------- 검색/재랭킹 + 필터 --------------------

    private QueryExpansionResult expandQuery(RagQueryRequest req, RagTimingCollector timings) {
        long rewriteStart = System.nanoTime();
        int topK = Optional.ofNullable(req.topK()).orElse(5);
        String ns = Optional.ofNullable(req.namespace()).filter(s -> !s.isBlank()).orElse(null);

        String queryRaw = Optional.ofNullable(req.query()).orElse("").strip();
        String query = urlDecodeDefensively(queryRaw);
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query must not be blank");
        }

        List<ScoredDoc> sparseCandidates = new ArrayList<>();
        List<String> expansions = expandQueries(query, topK, sparseCandidates);
        if (expansions.size() > 3) {
            expansions = expansions.subList(0, 3);
        }
        log.debug("[RAG] expansions({}): {}", expansions.size(), expansions);
        timings.setQueryExpansionMs(toMs(rewriteStart, System.nanoTime()));

        int perQueryK = Math.max(10, topK * 3);
        List<ScoredDoc> denseCandidates = new ArrayList<>();
        return new QueryExpansionResult(query, expansions, topK, perQueryK, ns, denseCandidates, sparseCandidates);
    }

    private RetrievalOutcome retrieveWithFilters(QueryExpansionResult expansionResult,
                                                 RagTimingCollector timings,
                                                 RagParallelStrategy strategy) throws Exception {
        RagQueryContext ctx = new RagQueryContext(
                expansionResult.query(),
                expansionResult.expansions(),
                expansionResult.topK(),
                expansionResult.perQueryK(),
                expansionResult.namespace(),
                denseTopK,
                sparseTopK,
                useMmr,
                mmrK,
                mmrLambda,
                useRerank,
                rerankTopK,
                expansionResult.denseAccumulator(),
                expansionResult.sparseAccumulator());

        String query = ctx.query();
        List<String> expansions = ctx.expansions();
        int topK = ctx.topK();

        RetrievalResult retrievalResult = runRetrievalWithStrategy(ctx, timings, strategy);

        int denseHits = retrievalResult.denseCandidates().size();
        int sparseHits = retrievalResult.sparseCandidates().size();
        log.info("[RAG][RETR] dense_raw_hits={}, sparse_raw_hits={}, query=\"{}\"", denseHits, sparseHits, query);

        List<ScoredDoc> all = new ArrayList<>(retrievalResult.fusedCandidates());

        // 🔧 키워드 필터: 원문 + 확장쿼리에서 키워드 추출 → 본문/제목/파일명에 포함된 것만
        String kwSource = query + " " + String.join(" ", expansions);
        Set<String> qKeywords = extractKeywords(kwSource);
        log.debug("[RAG] keyword filter size={} (sample={})",
                qKeywords.size(),
                qKeywords.stream().limit(10).collect(toList()));
        List<ScoredDoc> afterKw = all;
        if (!qKeywords.isEmpty()) {
            List<ScoredDoc> kwFiltered = all.stream()
                    .filter(sd -> matchesKeywords(sd, qKeywords))
                    .collect(toList());
            if (!kwFiltered.isEmpty())
                afterKw = kwFiltered; // 결과가 있으면 키워드 필터 적용, 아니면 폴백
        }

        // 중복 제거
        LinkedHashMap<String, ScoredDoc> uniq = new LinkedHashMap<>();
        for (ScoredDoc d : afterKw) {
            String key = Optional.ofNullable(d.id()).orElse("");
            if (key.isBlank()) {
                String filename = meta(d, "filename");
                String source = firstNonBlank(meta(d, "source"), meta(d, "url"), "");
                key = d.content().hashCode() + "|" + source + "|" + filename;
            }
            uniq.putIfAbsent(key, d);
        }
        List<ScoredDoc> deduped = new ArrayList<>(uniq.values());
        log.debug("[RAG] candidates after dedup: {}", deduped.size());

        // ---------------- 재랭킹 (RRF → MMR diversity → 옵션 reranker) ----------------

        // 1차: 스코어 기준 정렬
        List<ScoredDoc> sorted = new ArrayList<>(deduped);
        sorted.sort(Comparator.comparing(ScoredDoc::score).reversed());

        // 2차: MMR 기반 diversity 선택 (옵션)
        List<ScoredDoc> baseList = new ArrayList<>(sorted);
        if (useMmr && baseList.size() > 1) {
            int mmrLimit = Math.min(mmrK, baseList.size());
            baseList = applyMmrDiversity(baseList, mmrLimit, mmrLambda);
            log.debug("[RAG] MMR applied: selected={}/{}", baseList.size(), sorted.size());
        }

        // 3차: rerankTopK만큼 컷오프
        List<ScoredDoc> topCandidates = baseList.subList(0, Math.min(rerankTopK, baseList.size()));

        // 4차: 외부 reranker(예: RBF 모델)에 한 번 더 넘겨서 최종 topK 선택 (옵션)
        List<ScoredDoc> picked;
        if (useRerank && reranker.isPresent()) {
            long rerankStart = System.nanoTime();
            picked = reranker.get().rerank(query, topCandidates, topK);
            timings.addRerankMs(toMs(rerankStart, System.nanoTime()));
        } else {
            picked = topCandidates.subList(0, Math.min(topK, topCandidates.size()));
        }

        List<ScoredDoc> fusedCandidates = new ArrayList<>(picked);

        // Cite 구성 + 동일 문서(title|url) 중복 제거 + URL sanitize
        LinkedHashMap<String, PromptBuilder.Cite> citeMap = new LinkedHashMap<>();
        for (ScoredDoc sd : picked) {
            String title = firstNonBlank(meta(sd, "filename"), meta(sd, "title"), "Reference");
            String rawUrl = firstNonBlank(meta(sd, "source"), meta(sd, "url"), "");
            String url = sanitizeUrl(rawUrl); // 로컬/파일 경로 제거

            String key = (title + "|" + url);
            citeMap.putIfAbsent(key, new PromptBuilder.Cite(
                    title,
                    url,
                    firstNonBlank(sd.metaString("snippet"), pickQuote(sd.content()))));
        }
        // denseCandidates: raw Qdrant (vector) results
        // sparseCandidates: raw Lucene (SparseStore) results before fusion
        // fusedCandidates : final merged list actually used as LLM context
        return new RetrievalOutcome(
                new ArrayList<>(citeMap.values()),
                new ArrayList<>(ctx.denseAccumulator()),
                new ArrayList<>(ctx.sparseAccumulator()),
                fusedCandidates);
    }

    private RetrievalResult retrieveSequentialStrategy(RagQueryContext ctx, RagTimingCollector timings) {
        List<ScoredDoc> all = new ArrayList<>();
        for (String qx : ctx.expansions()) {
            var denseResults = timedDenseSearch(qx, ctx, timings);
            var sparseResults = timedSparseSearch(qx, ctx, timings);
            long mergeStart = System.nanoTime();
            var hybridResult = hybrid.mergeAndRank(
                    denseResults,
                    sparseResults,
                    ctx.useMmr(),
                    ctx.mmrK(),
                    ctx.mmrLambda(),
                    ctx.perQueryK());
            timings.addMergeMs(toMs(mergeStart, System.nanoTime()));

            List<ScoredDoc> denseResultsSafe = Optional.ofNullable(hybridResult.denseResults()).orElse(List.of());
            List<ScoredDoc> sparseResultsSafe = Optional.ofNullable(hybridResult.sparseResults()).orElse(List.of());

            ctx.denseAccumulator().addAll(denseResultsSafe);
            ctx.sparseAccumulator().addAll(sparseResultsSafe);

            collectFusedByNamespace(hybridResult.fusedResults(), ctx.namespace(), ctx.perQueryK(), all);
        }
        return new RetrievalResult(all, ctx.denseAccumulator(), ctx.sparseAccumulator());
    }

    private RetrievalResult retrieveParallelOldSafe(RagQueryContext ctx, RagTimingCollector timings) {
        List<ScoredDoc> all = new ArrayList<>();
        for (String qx : ctx.expansions()) {
            CompletableFuture<List<ScoredDoc>> denseFuture = CompletableFuture.supplyAsync(() -> {
                long start = System.nanoTime();
                try {
                    return denseRetriever.search(qx, ctx.denseTopK());
                } finally {
                    timings.addDenseMs(toMs(start, System.nanoTime()));
                }
            }, ragExecutor);

            CompletableFuture<List<ScoredDoc>> sparseFuture = CompletableFuture.supplyAsync(() -> {
                long start = System.nanoTime();
                try {
                    return sparseRetriever.search(qx, ctx.sparseTopK());
                } finally {
                    timings.addSparseMs(toMs(start, System.nanoTime()));
                }
            }, ragExecutor);

            List<ScoredDoc> denseResults;
            List<ScoredDoc> sparseResults;
            try {
                denseResults = denseFuture.get(30, TimeUnit.SECONDS);
                sparseResults = sparseFuture.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[RAG][PARALLEL_OLD] retrieval failed; falling back to sequential. mode=PARALLEL strategy=V1_OLD query={}", qx, e);
                return retrieveSequentialStrategy(ctx, timings);
            }

            long mergeStart = System.nanoTime();
            HybridRetriever.HybridResult hybridResult = hybrid.mergeAndRank(
                    denseResults,
                    sparseResults,
                    ctx.useMmr(),
                    ctx.mmrK(),
                    ctx.mmrLambda(),
                    ctx.perQueryK());
            timings.addMergeMs(toMs(mergeStart, System.nanoTime()));

            List<ScoredDoc> denseResultsSafe = Optional.ofNullable(hybridResult.denseResults()).orElse(List.of());
            List<ScoredDoc> sparseResultsSafe = Optional.ofNullable(hybridResult.sparseResults()).orElse(List.of());

            ctx.denseAccumulator().addAll(denseResultsSafe);
            ctx.sparseAccumulator().addAll(sparseResultsSafe);

            collectFusedByNamespace(hybridResult.fusedResults(), ctx.namespace(), ctx.perQueryK(), all);
        }
        return new RetrievalResult(all, ctx.denseAccumulator(), ctx.sparseAccumulator());
    }

    private RetrievalResult retrieveAsyncStrategy(RagQueryContext ctx, RagTimingCollector timings) {
        List<CompletableFuture<HybridRetriever.HybridResult>> tasks = new ArrayList<>();
        for (String qx : ctx.expansions()) {
            tasks.add(hybridSearchAsync(qx, ctx, timings));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
        try {
            all.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[RAG][ASYNC] retrieval failed or timed out; falling back to sequential. mode=PARALLEL strategy=ASYNC query={}", ctx.query(), e);
            return retrieveSequentialStrategy(ctx, timings);
        }

        List<ScoredDoc> allFused = new ArrayList<>();
        for (CompletableFuture<HybridRetriever.HybridResult> task : tasks) {
            HybridRetriever.HybridResult hybridResult = task.getNow(new HybridRetriever.HybridResult(List.of(), List.of(), List.of()));
            List<ScoredDoc> denseResults = Optional.ofNullable(hybridResult.denseResults()).orElse(List.of());
            List<ScoredDoc> sparseResults = Optional.ofNullable(hybridResult.sparseResults()).orElse(List.of());
            ctx.denseAccumulator().addAll(denseResults);
            ctx.sparseAccumulator().addAll(sparseResults);
            collectFusedByNamespace(hybridResult.fusedResults(), ctx.namespace(), ctx.perQueryK(), allFused);
        }
        return new RetrievalResult(allFused, ctx.denseAccumulator(), ctx.sparseAccumulator());
    }

    private RetrievalResult runRetrievalWithStrategy(RagQueryContext ctx,
                                                     RagTimingCollector timings,
                                                     RagParallelStrategy strategy) {
        return switch (strategy) {
            case SEQUENTIAL -> retrieveSequentialStrategy(ctx, timings);
            case V1_OLD -> retrieveParallelOldSafe(ctx, timings);
            case V2_ASYNC, ASYNC -> retrieveAsyncStrategy(ctx, timings);
        };
    }

    private List<HybridRetriever.HybridResult> searchAllExpansionsParallel(List<String> expansions, RagQueryContext ctx, RagTimingCollector timings) {
        List<CompletableFuture<HybridRetriever.HybridResult>> futures = expansions.stream()
                .map(expanded -> CompletableFuture.supplyAsync(
                        () -> hybridSearchParallel(expanded, ctx, timings),
                        ragExecutor))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private HybridRetriever.HybridResult hybridSearchParallel(String query, RagQueryContext ctx, RagTimingCollector timings) {
        if (nestedDenseSparse) {
            CompletableFuture<TimedResult<List<ScoredDoc>>> denseFuture =
                    CompletableFuture.supplyAsync(
                            () -> timedCall(() -> denseRetriever.search(query, ctx.denseTopK())),
                            ragExecutor);

            CompletableFuture<TimedResult<List<ScoredDoc>>> sparseFuture =
                    CompletableFuture.supplyAsync(
                            () -> timedCall(() -> sparseRetriever.search(query, ctx.sparseTopK())),
                            ragExecutor);

            return denseFuture.thenCombine(sparseFuture, (denseResult, sparseResult) -> {
                timings.addDenseMs(denseResult.elapsedMs());
                timings.addSparseMs(sparseResult.elapsedMs());
                long mergeStart = System.nanoTime();
                HybridRetriever.HybridResult merged = hybrid.mergeAndRank(
                        denseResult.result(),
                        sparseResult.result(),
                        ctx.useMmr(),
                        ctx.mmrK(),
                        ctx.mmrLambda(),
                        ctx.perQueryK());
                timings.addMergeMs(toMs(mergeStart, System.nanoTime()));
                return merged;
            }).exceptionally(ex -> {
                log.warn("[RAG] Hybrid search failed for query={} error={}", query, ex.getMessage());
                return new HybridRetriever.HybridResult(List.of(), List.of(), List.of());
            }).join();
        } else {
            return CompletableFuture.supplyAsync(() -> {
                TimedResult<List<ScoredDoc>> denseResult =
                        timedCall(() -> denseRetriever.search(query, ctx.denseTopK()));
                timings.addDenseMs(denseResult.elapsedMs());

                TimedResult<List<ScoredDoc>> sparseResult =
                        timedCall(() -> sparseRetriever.search(query, ctx.sparseTopK()));
                timings.addSparseMs(sparseResult.elapsedMs());

                long mergeStart = System.nanoTime();
                HybridRetriever.HybridResult merged = hybrid.mergeAndRank(
                        denseResult.result(),
                        sparseResult.result(),
                        ctx.useMmr(),
                        ctx.mmrK(),
                        ctx.mmrLambda(),
                        ctx.perQueryK());
                timings.addMergeMs(toMs(mergeStart, System.nanoTime()));
                return merged;
            }, ragExecutor).exceptionally(ex -> {
                log.warn("[RAG] Hybrid search failed for query={} error={}", query, ex.getMessage());
                return new HybridRetriever.HybridResult(List.of(), List.of(), List.of());
            }).join();
        }
    }

    private CompletableFuture<List<HybridRetriever.HybridResult>> searchAllExpansionsAsync(List<String> expansions, RagQueryContext ctx, RagTimingCollector timings) {
        List<CompletableFuture<HybridRetriever.HybridResult>> futures = expansions.stream()
                .map(expanded -> hybridSearchAsync(expanded, ctx, timings))
                .toList();

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

        return all.thenApply(v -> futures.stream()
                        .map(future -> future.handle((result, ex) -> {
                            if (ex != null) {
                                log.warn("[RAG] Expansion retrieval failed: query={} error={}", ctx.query(), ex.getMessage());
                                return null;
                            }
                            return result;
                        }))
                        .map(CompletableFuture::join)
                        .filter(Objects::nonNull)
                        .toList());
    }

    private CompletableFuture<HybridRetriever.HybridResult> hybridSearchAsync(String query, RagQueryContext ctx, RagTimingCollector timings) {
        if (nestedDenseSparse) {
            CompletableFuture<TimedResult<List<ScoredDoc>>> denseFuture =
                    CompletableFuture.supplyAsync(
                            () -> timedCall(() -> denseRetriever.search(query, ctx.denseTopK())),
                            ragExecutor);

            CompletableFuture<TimedResult<List<ScoredDoc>>> sparseFuture =
                    CompletableFuture.supplyAsync(
                            () -> timedCall(() -> sparseRetriever.search(query, ctx.sparseTopK())),
                            ragExecutor);

            return denseFuture.thenCombine(sparseFuture, (denseResult, sparseResult) -> {
                timings.addDenseMs(denseResult.elapsedMs());
                timings.addSparseMs(sparseResult.elapsedMs());
                long mergeStart = System.nanoTime();
                HybridRetriever.HybridResult merged = hybrid.mergeAndRank(
                        denseResult.result(),
                        sparseResult.result(),
                        ctx.useMmr(),
                        ctx.mmrK(),
                        ctx.mmrLambda(),
                        ctx.perQueryK());
                timings.addMergeMs(toMs(mergeStart, System.nanoTime()));
                return merged;
            }).exceptionally(ex -> {
                log.warn("[RAG] Hybrid search failed for query={} error={}", query, ex.getMessage());
                return new HybridRetriever.HybridResult(List.of(), List.of(), List.of());
            });
        } else {
            return CompletableFuture.supplyAsync(() -> {
                TimedResult<List<ScoredDoc>> denseResult =
                        timedCall(() -> denseRetriever.search(query, ctx.denseTopK()));
                timings.addDenseMs(denseResult.elapsedMs());

                TimedResult<List<ScoredDoc>> sparseResult =
                        timedCall(() -> sparseRetriever.search(query, ctx.sparseTopK()));
                timings.addSparseMs(sparseResult.elapsedMs());

                long mergeStart = System.nanoTime();
                HybridRetriever.HybridResult merged = hybrid.mergeAndRank(
                        denseResult.result(),
                        sparseResult.result(),
                        ctx.useMmr(),
                        ctx.mmrK(),
                        ctx.mmrLambda(),
                        ctx.perQueryK());
                timings.addMergeMs(toMs(mergeStart, System.nanoTime()));
                return merged;
            }, ragExecutor).exceptionally(ex -> {
                log.warn("[RAG] Hybrid search failed for query={} error={}", query, ex.getMessage());
                return new HybridRetriever.HybridResult(List.of(), List.of(), List.of());
            });
        }
    }

    private List<ScoredDoc> timedDenseSearch(String query, RagQueryContext ctx, RagTimingCollector timings) {
        TimedResult<List<ScoredDoc>> result = timedCall(() -> denseRetriever.search(query, ctx.denseTopK()));
        timings.addDenseMs(result.elapsedMs());
        return result.result();
    }

    private List<ScoredDoc> timedSparseSearch(String query, RagQueryContext ctx, RagTimingCollector timings) {
        TimedResult<List<ScoredDoc>> result = timedCall(() -> sparseRetriever.search(query, ctx.sparseTopK()));
        timings.addSparseMs(result.elapsedMs());
        return result.result();
    }

    private <T> TimedResult<T> timedCall(Supplier<T> supplier) {
        long start = System.nanoTime();
        T result = supplier.get();
        long end = System.nanoTime();
        return new TimedResult<>(result, toMs(start, end));
    }

    private void collectFusedByNamespace(List<ScoredDoc> fused,
                                         String namespace,
                                         int perQueryK,
                                         List<ScoredDoc> accumulator) {
        Optional.ofNullable(fused).orElse(List.of()).stream()
                .filter(sd -> {
                    if (namespace == null)
                        return true;
                    Map<String, Object> m = sd.meta();
                    String docNs = String.valueOf(m == null ? "default" : m.getOrDefault("namespace", "default"));
                    return docNs.equals(namespace);
                })
                .limit(perQueryK)
                .forEach(accumulator::add);
    }

    private record QueryExpansionResult(
            String query,
            List<String> expansions,
            int topK,
            int perQueryK,
            String namespace,
            List<ScoredDoc> denseAccumulator,
            List<ScoredDoc> sparseAccumulator) {
    }

    private record RagQueryContext(
            String query,
            List<String> expansions,
            int topK,
            int perQueryK,
            String namespace,
            int denseTopK,
            int sparseTopK,
            boolean useMmr,
            int mmrK,
            double mmrLambda,
            boolean useRerank,
            int rerankTopK,
            List<ScoredDoc> denseAccumulator,
            List<ScoredDoc> sparseAccumulator) {
    }

    private record RetrievalResult(
            List<ScoredDoc> fusedCandidates,
            List<ScoredDoc> denseCandidates,
            List<ScoredDoc> sparseCandidates) {
    }

    private record TimedResult<T>(T result, long elapsedMs) {
    }

    private static class RagTimingCollector {
        // Stage mapping: queryExpansionMs (pre-retrieval rewrite), denseMs/sparseMs (vector/BM25 lookups),
        // mergeMs (dense+sparse fusion), rerankMs (external reranker/model), llmMs (generation),
        // guardrailMs (content safety), retrievalMs (wall-clock retrieval span), contextMs (prompt build), totalMs (end-to-end).
        private long totalMs;
        private long queryExpansionMs;
        private long retrievalMs;
        private long denseMs;
        private long sparseMs;
        private long mergeMs;
        private long rerankMs;
        private long contextMs;
        private long llmMs;
        private long guardrailMs;

        void setTotalMs(long totalMs) {
            this.totalMs = totalMs;
        }

        void setQueryExpansionMs(long queryExpansionMs) {
            this.queryExpansionMs = queryExpansionMs;
        }

        void setRetrievalMs(long retrievalMs) {
            this.retrievalMs = retrievalMs;
        }

        void addDenseMs(long denseMs) {
            this.denseMs += denseMs;
        }

        void addSparseMs(long sparseMs) {
            this.sparseMs += sparseMs;
        }

        void addMergeMs(long mergeMs) {
            this.mergeMs += mergeMs;
        }

        void addRerankMs(long rerankMs) {
            this.rerankMs += rerankMs;
        }

        void addGuardrailMs(long guardrailMs) {
            this.guardrailMs += guardrailMs;
        }

        void setContextMs(long contextMs) {
            this.contextMs = contextMs;
        }

        void setLlmMs(long llmMs) {
            this.llmMs = llmMs;
        }

        RagTimingRecord toRecord(String executionMode,
                                 String strategy,
                                 int threadPoolSize,
                                 ParallelGranularity granularity,
                                 boolean nestedDenseSparse) {
            RagTimingRecord record = new RagTimingRecord();
            record.setExecutionMode(executionMode);
            record.setParallelStrategy(strategy);
            record.setTotalMs(totalMs);
            record.setQueryExpansionMs(queryExpansionMs);
            record.setRetrievalMs(retrievalMs);
            record.setDenseMs(denseMs);
            record.setSparseMs(sparseMs);
            record.setMergeMs(mergeMs);
            record.setRerankMs(rerankMs);
            record.setContextBuildMs(contextMs);
            record.setLlmMs(llmMs);
            record.setGuardrailMs(guardrailMs);
            record.setThreadPoolSize(threadPoolSize);
            record.setParallelGranularity(granularity.name());
            record.setNestedDenseSparse(nestedDenseSparse);
            return record;
        }
    }

    // -------------------- 질의 확장 --------------------

    /**
     * 쿼리 확장 (캐싱 적용)
     * - LLM 리라이트와 PRF 검색 결과를 캐싱하여 동일 쿼리 재요청 시 성능 향상
     * - 캐시 키: 원본 쿼리
     * - 캐시 만료: 기본 설정 (메모리 기반 무제한)
     */
    @Cacheable(value = "rag-query-expansion", key = "#q")
    private List<String> expandQueries(String q, int topK, List<ScoredDoc> sparseAccumulator) {
        log.debug("[RAG캐시] 쿼리 확장 캐시 미스 - 새로 생성: query={} granularity={}", q, parallelGranularity);
        return (parallelGranularity == ParallelGranularity.COARSE)
                ? expandQueriesCoarse(q, topK, sparseAccumulator)
                : expandQueriesFine(q, topK, sparseAccumulator);
    }

    /**
     * COARSE: 모든 단계 순차 실행 (스레드 사용 최소화)
     */
    private List<String> expandQueriesCoarse(String q, int topK, List<ScoredDoc> sparseAccumulator) {
        String base = Optional.ofNullable(q).orElse("").trim();
        if (base.isEmpty()) {
            return List.of(base);
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(base);

        for (String variant : expandRuleBased(base)) {
            String cleaned = cleanVariant(variant);
            if (!cleaned.isBlank()) out.add(cleaned);
        }

        for (String variant : safeLlmVariants(base)) {
            String cleaned = cleanVariant(variant);
            if (!cleaned.isBlank()) out.add(cleaned);
        }

        for (String variant : safePrfBoosts(base, topK, sparseAccumulator)) {
            String cleaned = cleanVariant(variant);
            if (!cleaned.isBlank()) out.add(cleaned);
        }

        return finalizeExpansions(base, out);
    }

    /**
     * FINE: 기존 V2_ASYNC 스타일 - 세부 단계 병렬 실행
     */
    private List<String> expandQueriesFine(String q, int topK, List<ScoredDoc> sparseAccumulator) {
        String base = Optional.ofNullable(q).orElse("").trim();
        if (base.isEmpty()) {
            return List.of(base);
        }

        // base 쿼리는 항상 포함
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(base);

        CompletableFuture<List<String>> ruleFuture =
                CompletableFuture.supplyAsync(() -> expandRuleBased(base), ragExecutor);

        CompletableFuture<List<String>> llmFuture =
                CompletableFuture.supplyAsync(() -> safeLlmVariants(base), ragExecutor);

        CompletableFuture<List<String>> prfFuture =
                CompletableFuture.supplyAsync(() -> safePrfBoosts(base, topK, sparseAccumulator), ragExecutor);

        try {
            CompletableFuture.allOf(ruleFuture, llmFuture, prfFuture).join();

            ruleFuture.get().forEach(s -> {
                String c = cleanVariant(s);
                if (!c.isBlank()) out.add(c);
            });

            llmFuture.get().forEach(s -> {
                String c = cleanVariant(s);
                if (!c.isBlank()) out.add(c);
            });

            prfFuture.get().forEach(s -> {
                String c = cleanVariant(s);
                if (!c.isBlank()) out.add(c);
            });
        } catch (Exception e) {
            log.warn("[RAG] expandQueries 병렬 처리 중 예외 발생: {}", e.getMessage());
        }

        return finalizeExpansions(base, out);
    }

    private List<String> finalizeExpansions(String base, LinkedHashSet<String> collected) {
        // ✅ 우선순위 정렬로 근거 중심/침 관련 쿼리를 앞으로
        List<String> prioritized = prioritizeExpansions(base, new ArrayList<>(collected));

        // 길이 제한 및 상한
        prioritized = prioritized.stream()
                .map(s -> s.length() > 180 ? s.substring(0, 180) : s)
                .collect(Collectors.toList());
        if (prioritized.size() > 12) {
            prioritized = prioritized.subList(0, 12);
        }
        return prioritized;
    }

    // 룰 기반 확장만 따로 분리
    private List<String> expandRuleBased(String base) {
        try {
            List<String> res = new ArrayList<>();
            for (String s : expander.expand(base)) {
                if (s != null && !s.isBlank()) {
                    res.add(s);
                }
            }
            return res;
        } catch (Exception e) {
            log.warn("[RAG] 룰 기반 쿼리 확장 실패: {}", e.getMessage());
            return List.of();
        }
    }

    // LLM 리라이트 안전 래퍼 (기존 getLlmQueryVariants 재사용)
    private List<String> safeLlmVariants(String base) {
        try {
            return getLlmQueryVariants(base); // @Cacheable, chat.chat 사용
        } catch (Exception e) {
            log.warn("[RAG] LLM 쿼리 리라이트 실패: {}", e.getMessage());
            return List.of();
        }
    }

    // PRF 기반 키워드 부스트 생성 (기존 getPrfSeeds 재사용)
    private List<String> safePrfBoosts(String base, int topK, List<ScoredDoc> sparseAccumulator) {
        try {
            int prfK = Math.max(8, topK * 4);
            List<ScoredDoc> seeds = getPrfSeeds(base, sparseAccumulator); // @Cacheable + hybrid.searchDetailed

            String joined = seeds.stream()
                    .limit(prfK)
                    .map(ScoredDoc::content)
                    .collect(Collectors.joining(" "));

            List<String> keywords = topKeywords(joined, 12);
            List<String> out = new ArrayList<>();
            if (!keywords.isEmpty()) {
                String boosted1 = base + " " + String.join(" ", keywords.subList(0, Math.min(6, keywords.size())));
                out.add(boosted1);
                if (keywords.size() > 6) {
                    String boosted2 = base + " " + String.join(" ", keywords.subList(6, Math.min(12, keywords.size())));
                    out.add(boosted2);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[RAG] PRF 기반 확장 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * LLM 쿼리 리라이트 (캐싱 적용)
     * - 동일 쿼리에 대한 LLM 호출 결과를 캐싱하여 2초 절약
     */
    @Cacheable(value = "rag-query-expansion", key = "'llm:' + #query")
    private List<String> getLlmQueryVariants(String query) {
        log.debug("[RAG캐시] LLM 리라이트 캐시 미스 - 새로 생성: query={}", query);
        try {
            String sys = "You are a query rewriting engine for retrieval. "
                    + "Return only a JSON array of 3-5 short diversified queries. "
                    + "Include paraphrases, common synonyms, acronyms, and cross-lingual variants (Korean/English) when helpful. "
                    + "Keep each under 12 words; no explanations.";
            String user = "Query: " + query;
            String json = chat.chat(sys, user);
            return tryParseStringArray(json);
        } catch (Exception e) {
            log.warn("[RAG캐시] LLM 리라이트 실패: query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * PRF 시드 문서 검색 (캐싱 적용)
     * - 동일 쿼리에 대한 PRF 검색 결과를 캐싱하여 1초 절약
     */
    @Cacheable(value = "rag-prf-seeds", key = "#query")
    private List<ScoredDoc> getPrfSeeds(String query, List<ScoredDoc> sparseAccumulator) {
        log.debug("[RAG캐시] PRF 시드 캐시 미스 - 새로 검색: query={}", query);
        var hybridResult = hybrid.searchDetailed(query, Math.min(20, denseTopK), Math.min(20, sparseTopK), false, 10, 0.4);

        Optional.ofNullable(sparseAccumulator)
                .ifPresent(acc -> acc.addAll(Optional.ofNullable(hybridResult.sparseResults()).orElse(List.of())));

        return Optional.ofNullable(hybridResult.fusedResults()).orElse(List.of());
    }

    private List<String> tryParseStringArray(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // JSON 배열이 아니면 줄단위로 폴백
            return Arrays.stream(json.split("\\r?\\n"))
                    .map(s -> s.replaceAll("^[-*\\s]+", "")) // bullet 제거
                    .filter(s -> !s.isBlank())
                    .collect(toList());
        }
    }

    /** 리라이트 문자열 정리(따옴표/브라켓/코드펜스/말미 구두점 등 제거) */
    private String cleanVariant(String s) {
        if (s == null)
            return "";
        String t = s.trim();

        // 코드펜스 전체 제거(멀티라인)
        t = t.replaceAll("(?s)^```.*?```\\s*$", "").trim();

        // 외곽 브라켓/괄호/여분 공백 제거
        t = t.replaceAll("^[\\[\\(\\{\\s]+", "")
                .replaceAll("[\\]\\)\\}\\s]+$", "")
                .trim();

        // 스마트/일반 따옴표/백틱 제거(양끝)
        t = t.replaceAll("^[\"“”‘’`]+", "")
                .replaceAll("[\"“”‘’`]+$", "")
                .trim();

        // 말미 구두점 전반 제거(루씬 파서 안정성)
        t = t.replaceAll("[\\p{Punct}\\p{IsPunctuation}]+$", "").trim();

        // 중복 공백 정리
        t = t.replaceAll("\\s{2,}", " ").trim();

        return t;
    }

    private static long toMs(long start, long end) {
        return TimeUnit.NANOSECONDS.toMillis(end - start);
    }

    private void logTimingMetric(RagTimingRecord record) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("mode", record.getExecutionMode());
            payload.put("strategy", record.getParallelStrategy());
            payload.put("totalMs", record.getTotalMs());
            payload.put("queryExpansionMs", record.getQueryExpansionMs());
            payload.put("retrievalMs", record.getRetrievalMs());
            payload.put("denseMs", record.getDenseMs());
            payload.put("sparseMs", record.getSparseMs());
            payload.put("mergeMs", record.getMergeMs());
            payload.put("rerankMs", record.getRerankMs());
            payload.put("contextMs", record.getContextBuildMs());
            payload.put("llmMs", record.getLlmMs());
            payload.put("guardrailMs", record.getGuardrailMs());
            payload.put("threadPoolSize", record.getThreadPoolSize());
            payload.put("parallelGranularity", record.getParallelGranularity());
            payload.put("nestedDenseSparse", record.isNestedDenseSparse());

            String json = MAPPER.writeValueAsString(payload);
            log.info("[RAG_TIMING] {}", json);
        } catch (Exception e) {
            log.warn("[RAG] Failed to log timing metric: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        String trimmed = text.strip();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private static List<String> topKeywords(String text, int limit) {
        if (text == null || text.isBlank())
            return List.of();
        Map<String, Integer> freq = new HashMap<>();
        var m = TOKEN.matcher(text);
        while (m.find()) {
            String tok = m.group().toLowerCase(Locale.ROOT);
            if (tok.length() < 2)
                continue;
            if (STOP_EN.contains(tok))
                continue;
            if (STOP_KO.contains(tok))
                continue;
            freq.merge(tok, 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(toList());
    }

    // -------------------- 키워드 필터 --------------------

    private Set<String> extractKeywords(String q) {
        if (q == null)
            return Set.of();
        LinkedHashSet<String> kws = new LinkedHashSet<>();
        var m = TOKEN.matcher(q);
        while (m.find()) {
            String tok = m.group().toLowerCase(Locale.ROOT);
            if (tok.length() < 2)
                continue;
            if (STOP_EN.contains(tok))
                continue;
            if (STOP_KO.contains(tok))
                continue;
            kws.add(tok);
        }
        return kws;
    }

    private boolean matchesKeywords(ScoredDoc d, Set<String> kws) {
        if (kws.isEmpty())
            return true;
        String title = meta(d, "title");
        String filename = meta(d, "filename");
        String hay = (nullToEmpty(d.content()) + " " + title + " " + filename).toLowerCase(Locale.ROOT);
        for (String k : kws) {
            if (hay.contains(k))
                return true;
        }
        return false;
    }

    // -------------------- 유틸 --------------------

    /**
     * 텍스트에서 첫 N개 문장 추출 (간단보기용)
     * - LLM 호출 없이 첫 2-3문장만 추출하여 3초 절약
     */
    private String extractFirstSentences(String text, int count) {
        if (text == null || text.isBlank()) {
            return "";
        }

        // 한글/영문 문장 종결 기준: ., !, ?, 。
        String[] sentences = text.split("[.!?。]\\s+");

        StringBuilder result = new StringBuilder();
        int limit = Math.min(count, sentences.length);
        for (int i = 0; i < limit; i++) {
            result.append(sentences[i].trim());
            if (i < limit - 1) {
                result.append(". ");
            }
        }

        return result.toString();
    }

    private String renderCombined(String compact, String detailed) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 간단보기\n").append(nullToEmpty(compact)).append("\n\n");
        sb.append("### 자세히보기\n").append(nullToEmpty(detailed));
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // 인용문: PDF 추출 텍스트 가독성 보정 + 280자 컷
    private String pickQuote(String text) {
        if (text == null)
            return "";
        String t = text
                .replaceAll("-\\s*\\n", "")
                .replaceAll("-\\s+", "")
                .replaceAll("\\s*\\n\\s*", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return t.length() > 280 ? t.substring(0, 280) + "…" : t;
    }

    private Map<String, Object> profileMap(RagQueryRequest req) {
        var p = req.profile();
        var m = new HashMap<String, Object>();
        if (p != null) {
            m.put("name", p.name());
            m.put("age", p.age());
            m.put("sex", p.sex());
            m.put("occupation", p.occupation());
            if (p.extras() != null)
                m.putAll(p.extras());
        }
        return m;
    }

    private static String meta(ScoredDoc d, String key) {
        Map<String, Object> m = d.meta();
        if (m == null)
            return "";
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String firstNonBlank(String... vals) {
        for (String s : vals)
            if (s != null && !s.isBlank())
                return s;
        return "";
    }

    /** URL 이중 인코딩 방어: 최대 2회까지 decode, decode로 값이 변하면 한 번 더 시도 */
    private static String urlDecodeDefensively(String s) {
        if (s == null || s.isBlank())
            return "";
        String prev = s;
        for (int i = 0; i < 2; i++) {
            try {
                String decoded = URLDecoder.decode(prev, StandardCharsets.UTF_8);
                if (decoded.equals(prev)) {
                    // 더 이상 변화 없음. 혹시 여전히 %XX가 남아있으면 한 번 더 시도 후 종료
                    if (PCT_ENC_PATTERN.matcher(decoded).find()) {
                        prev = decoded;
                        continue;
                    }
                    return decoded;
                } else {
                    prev = decoded;
                }
            } catch (IllegalArgumentException e) {
                // 잘못된 인코딩이면 원문 반환
                return prev;
            }
        }
        return prev;
    }

    // -------------------- 확장 쿼리 우선순위 정렬 --------------------

    /** 침/전침, 근거(RCT/메타/PSQI 등) 포함 쿼리를 상위에 오도록 정렬하고 base는 항상 0번 인덱스. */
    private List<String> prioritizeExpansions(String base, List<String> items) {
        if (items == null || items.isEmpty())
            return List.of(base);

        // 정리
        items.removeIf(Objects::isNull);
        items.replaceAll(String::trim);
        items.removeIf(String::isBlank);

        // base는 맨 앞으로 고정
        items.remove(base);

        Comparator<String> cmp = (a, b) -> {
            int sa = score(a);
            int sb = score(b);
            if (sa != sb)
                return Integer.compare(sb, sa); // 높은 점수 우선
            // 동점이면 짧은 쿼리 우선(잡단어/군더더기 억제)
            int len = Integer.compare(a.length(), b.length());
            if (len != 0)
                return len;
            return a.compareToIgnoreCase(b);
        };

        // 중복 제거 후 정렬
        List<String> uniq = new ArrayList<>(new LinkedHashSet<>(items));
        uniq.sort(cmp);

        List<String> out = new ArrayList<>();
        out.add(base);
        out.addAll(uniq);
        return out;
    }

    /** 간단 스코어러: 침/전침 > 폐경·불면 조합 > 근거(RCT/메타/지표) > CBT */
    private int score(String s) {
        if (s == null || s.isBlank())
            return 0;
        String t = s.toLowerCase(Locale.ROOT);

        String[] hiAcu = { "acupuncture", "electroacupuncture", "전침", "침술", "침 " };
        String[] hiMenIns = { "menopausal insomnia", "갱년기 불면", "폐경기 불면" };
        String[] hiEvidence = { "rct", "randomized controlled", "무작위대조", "systematic review", "meta-analysis", "메타분석",
                "체계적 문헌고찰" };
        String[] hiMetrics = { "psqi", "isi" };
        String[] hiCBT = { "cbt-i", "cbt i", "cbt" };

        int sc = 0;
        for (String k : hiAcu)
            if (t.contains(k))
                sc += 5;
        for (String k : hiMenIns)
            if (t.contains(k))
                sc += 4;
        for (String k : hiEvidence)
            if (t.contains(k))
                sc += 3;
        for (String k : hiMetrics)
            if (t.contains(k))
                sc += 3;
        for (String k : hiCBT)
            if (t.contains(k))
                sc += 2;
        return sc;
    }

    /**
     * 간단한 MMR(Maximal Marginal Relevance) 기반 diversity 선택
     * - relevance: ScoredDoc.score() 사용
     * - redundancy: 제목/파일명/본문에서 토큰 셋을 만들어 Jaccard 유사도로 근사
     */
    private List<ScoredDoc> applyMmrDiversity(List<ScoredDoc> docs, int k, double lambda) {
        if (docs == null || docs.isEmpty() || k <= 0) {
            return List.of();
        }
        List<ScoredDoc> selected = new ArrayList<>();
        List<Set<String>> tokenSets = docs.stream()
                .map(this::tokensForMmr)
                .collect(toList());

        // 첫 번째 문서는 가장 relevance 높은 문서 (이미 score 기준 정렬되어 있음)
        selected.add(docs.get(0));

        while (selected.size() < Math.min(k, docs.size())) {
            double bestScore = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;

            for (int i = 0; i < docs.size(); i++) {
                ScoredDoc candidate = docs.get(i);
                if (selected.contains(candidate)) {
                    continue;
                }

                double relevance = candidate.score(); // ScoringUtils.normalize로 0~1 근처라고 가정
                double redundancy = 0.0;

                Set<String> candTokens = tokenSets.get(i);
                for (ScoredDoc sel : selected) {
                    int selIdx = docs.indexOf(sel);
                    if (selIdx < 0)
                        continue;
                    Set<String> selTokens = tokenSets.get(selIdx);
                    redundancy = Math.max(redundancy, jaccardSimilarity(candTokens, selTokens));
                }

                double mmrScore = lambda * relevance - (1.0 - lambda) * redundancy;

                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestIdx = i;
                }
            }

            if (bestIdx < 0) {
                break;
            }
            selected.add(docs.get(bestIdx));
        }

        return selected;
    }

    /** MMR용 토큰 추출: 제목/파일명/본문 일부를 합쳐서 간단히 분해 */
    private Set<String> tokensForMmr(ScoredDoc d) {
        String title = meta(d, "title");
        String filename = meta(d, "filename");
        String text = (nullToEmpty(d.content()) + " " + title + " " + filename)
                .toLowerCase(Locale.ROOT);

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        var m = TOKEN.matcher(text);
        while (m.find()) {
            String tok = m.group().toLowerCase(Locale.ROOT);
            if (tok.length() < 2)
                continue;
            if (STOP_EN.contains(tok))
                continue;
            if (STOP_KO.contains(tok))
                continue;
            tokens.add(tok);
        }
        return tokens;
    }

    /** Jaccard 유사도: |A∩B| / |A∪B| */
    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        if (inter.isEmpty())
            return 0.0;

        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    /** file://, 로컬 경로 등 비-웹 URL은 숨김 */
    private static String sanitizeUrl(String url) {
        if (url == null || url.isBlank())
            return "";
        String u = url.trim();

        // 로컬 파일/경로 형태는 숨김
        if (u.startsWith("file:") || u.startsWith("C:\\") || u.startsWith("/") || u.startsWith("\\")) {
            return "";
        }
        // http(s) 또는 DOI만 허용
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("doi:")) {
            return u;
        }
        return "";
    }
}
