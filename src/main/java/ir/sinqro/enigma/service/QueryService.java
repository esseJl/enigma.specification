package ir.sinqro.enigma.service;

import ir.sinqro.enigma.annotation.DtoMapper;
import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.model.*;
import ir.sinqro.enigma.repository.GenericRepository;
import ir.sinqro.enigma.specification.CollectionProjectionLoader;
import ir.sinqro.enigma.specification.CollectionProjectionPlan;
import ir.sinqro.enigma.specification.CollectionProjectionPlanner;
import ir.sinqro.enigma.specification.DtoMapperHelper;
import ir.sinqro.enigma.specification.JoinContext;
import ir.sinqro.enigma.specification.SpecificationBuilder;
import ir.sinqro.enigma.utils.response.ResponseUtils;
import jakarta.annotation.Nonnull;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;


public abstract class QueryService<T, ID, D> {

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private SpecificationBuilder specBuilder;
    @Autowired
    private DtoMapperHelper dtoMapperHelper;
    @Autowired
    private CollectionProjectionPlanner collectionPlanner;
    @Autowired
    private CollectionProjectionLoader collectionLoader;
    @Autowired(required = false)
    private ResponseUtils responseUtils;

    private final GenericRepository<T, ID> repository;
    private final Class<D> dtoClass;

    protected QueryService(GenericRepository<T, ID> repository, Class<D> dtoClass) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.dtoClass = Objects.requireNonNull(dtoClass, "dtoClass must not be null");
    }

    protected QueryRequest internalFilter() {
        return QueryRequest.of(null);
    }


    @Transactional(readOnly = true)
    public PageResponse<D> query(QueryRequest request) {
        return queryAs(request, dtoClass);
    }


    @Transactional(readOnly = true)
    public Map<String, Object> queryPageable(QueryRequest request) {
        if (responseUtils == null) {
            throw new IllegalStateException(
                    "ResponseUtils bean در context پیدا نشد. یا یک بین ResponseUtils تعریف کنید "
                            + "یا مستقیماً از query(QueryRequest) استفاده کنید.");
        }
        return responseUtils.getResponsePageable(toSpringPage(query(request)));
    }


    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public <X> PageResponse<X> queryAs(QueryRequest request, Class<X> dtoOrEntity) {
        requireDtoOrEntity(dtoOrEntity);

        QueryRequest safeRequest = request != null ? request : QueryRequest.of(null);
        QueryRequest internalRequest = internalFilter();

        List<SortRequest> clientSorts = safeRequest.sort();
        if (clientSorts != null && clientSorts.size() > specBuilder.maxSortFields()) {
            throw new QueryRuleException(
                    "Too many sort fields: " + clientSorts.size()
                            + " (max allowed: " + specBuilder.maxSortFields() + ")");
        }

        PageRequest pageReq = safeRequest.page();
        Sort springSort = buildSort(clientSorts, internalRequest.sort(), dtoOrEntity);
        Pageable pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(0, pageReq.page()), pageReq.size(), springSort);

        boolean isDtoProjection = dtoOrEntity.isAnnotationPresent(DtoMapper.class);

        Page<X> result;
        if (isDtoProjection) {
            result = findProjected(safeRequest.filter(), internalRequest, pageable, dtoOrEntity);
        } else {
            Specification<T> finalSpec = getFinalSpec(dtoOrEntity, safeRequest, internalRequest);

            result = (Page<X>) repository.findAll(finalSpec, pageable);
        }

        return PageResponse.of(result.getContent(), result.getTotalElements(), pageReq);
    }

    @Nonnull
    private <X> Specification<T> getFinalSpec(Class<X> dtoOrEntity, QueryRequest safeRequest, QueryRequest internalRequest) {
        final FilterNode clientFilter = safeRequest.filter();
        return (root, query, cb) -> {
            JoinContext joinCtx = new JoinContext(specBuilder.maxJoins());

            Predicate clientPredicate = null;
            if (clientFilter != null) {
                QueryRequest clientOnly = new QueryRequest(clientFilter, List.of(), PageRequest.firstPage());
                clientPredicate = specBuilder
                        .buildSpecificationWithJoin(clientOnly, dtoOrEntity, joinCtx)
                        .toPredicate((Root<Object>) root, query, cb);
            }
            Predicate internalPredicate = specBuilder
                    .buildSpecificationWithJoin(internalRequest, null, joinCtx)
                    .toPredicate((Root<Object>) root, query, cb);

            if (joinCtx.hasCollectionJoin()) {
                query.distinct(true);
            }

            if (clientPredicate == null) return internalPredicate;
            if (internalPredicate == null) return clientPredicate;
            return cb.and(clientPredicate, internalPredicate);
        };
    }


    protected FilterNode mergeFilters(FilterNode original, List<FilterNode> extras) {
        List<FilterNode> all = new ArrayList<>(extras);
        if (original != null) {
            all.add(original);
        }
        if (all.isEmpty()) {
            return null;
        }
        if (all.size() == 1) {
            return all.get(0);
        }
        return new FilterGroup(LogicalOperator.AND, all);
    }

    protected QueryRequest mergeFilter(QueryRequest request, FilterNode extra) {
        FilterNode merged = mergeFilters(request.filter(), List.of(extra));
        return new QueryRequest(merged, request.sort(), request.page());
    }


    private static void requireDtoOrEntity(Class<?> dtoOrEntity) {
        Objects.requireNonNull(dtoOrEntity,
                "dtoOrEntity must not be null. Provide either the entity class or a class annotated with "
                        + "@DtoMapper — this is required so client-supplied filter/sort fields are always "
                        + "validated against a whitelist and cannot bypass field security checks.");
    }

    private Page<D> toSpringPage(PageResponse<D> response) {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(response.page(), response.size());
        return new PageImpl<>(response.result(), pageable, response.count());
    }

    private Sort buildSort(List<SortRequest> clientSorts, List<SortRequest> internalSorts, Class<?> dtoOrEntity) {
        List<Sort.Order> clientOrders = toOrders(clientSorts, dtoOrEntity);
        List<Sort.Order> internalOrders = toOrders(internalSorts, null);

        if (internalOrders.isEmpty()) {
            return clientOrders.isEmpty() ? Sort.unsorted() : Sort.by(clientOrders);
        }
        if (clientOrders.isEmpty()) {
            return Sort.by(internalOrders);
        }

        Set<String> clientProperties = clientOrders.stream()
                .map(Sort.Order::getProperty)
                .collect(Collectors.toSet());

        List<Sort.Order> merged = new ArrayList<>(clientOrders);
        for (Sort.Order internal : internalOrders) {
            if (!clientProperties.contains(internal.getProperty())) {
                merged.add(internal);
            }
        }
        return Sort.by(merged);
    }

    private List<Sort.Order> toOrders(List<SortRequest> sorts, Class<?> dtoOrEntity) {
        if (sorts == null || sorts.isEmpty()) {
            return List.of();
        }
        List<Sort.Order> orders = new ArrayList<>(sorts.size());
        for (SortRequest s : sorts) {
            if (s.field() == null || s.field().isBlank()) {
                continue;
            }
            String path = specBuilder.resolveSortPath(s.field(), dtoOrEntity);
            Sort.Direction dir = (s.direction() == SortDirection.DESC) ? Sort.Direction.DESC : Sort.Direction.ASC;
            orders.add(new Sort.Order(dir, path));
        }
        return orders;
    }

    @SuppressWarnings("unchecked")
    private <X> Page<X> findProjected(FilterNode clientFilter, QueryRequest internalRequest, Pageable pageable, Class<?> dtoClass) {

        Class<T> entityClass = (Class<T>) specBuilder.resolveEntityClass(dtoClass);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        JoinContext joinCtx = new JoinContext(specBuilder.maxJoins());

        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<T> root = cq.from(entityClass);

        List<FieldMeta> metas = specBuilder.getProjectionFieldMetas(dtoClass);
        List<Selection<?>> selections = new ArrayList<>(
                specBuilder.buildProjectionSelectionsWithAlias(root, dtoClass, joinCtx));

        // @ProjectedCollection fields: select the owner id as one extra, trailing column
        final CollectionProjectionPlan collectionPlan = collectionPlanner.planFor(dtoClass);
        final int ownerIdColumn;
        if (collectionPlan.hasCollections()) {
            String idAttr = collectionLoader.requireIdAttribute(entityClass);
            int existingIdColumn = -1;
            for (int i = 0; i < metas.size(); i++) {
                if (idAttr.equals(metas.get(i).entityPath())) {
                    existingIdColumn = i;
                    break;
                }
            }
            if (existingIdColumn >= 0) {
                // The id is already projected by the DTO: reuse that column.
                ownerIdColumn = existingIdColumn;
            } else {
                ownerIdColumn = selections.size();
                selections.add(root.get(idAttr));
            }
        } else {
            ownerIdColumn = -1;
        }
        cq.multiselect(selections);

        Specification<T> clientSpec = null;
        if (clientFilter != null) {
            QueryRequest clientOnly = new QueryRequest(
                    clientFilter,
                    List.of(),
                    PageRequest.firstPage());
            clientSpec = specBuilder.buildSpecificationWithJoin(clientOnly, dtoClass, joinCtx);
        }

        Specification<T> internalSpec = specBuilder.buildSpecificationWithJoin(
                internalRequest, null, joinCtx);

        Specification<T> finalSpec = andSpecs(clientSpec, internalSpec);

        if (finalSpec != null) {
            Predicate predicate = finalSpec.toPredicate(root, cq, cb);
            if (predicate != null) {
                cq.where(predicate);
            }
        }

        if (joinCtx.hasCollectionJoin()) {
            cq.distinct(true);
        }

        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            for (Sort.Order order : pageable.getSort()) {
                Path<?> path = specBuilder.resolvePathWithJoin(root, order.getProperty(), joinCtx);
                orders.add(order.isAscending() ? cb.asc(path) : cb.desc(path));
            }
            cq.orderBy(orders);
        }

        TypedQuery<Tuple> dataQuery = entityManager.createQuery(cq);
        dataQuery.setFirstResult(safeOffset(pageable));
        dataQuery.setMaxResults(pageable.getPageSize());
        List<Tuple> tuples = dataQuery.getResultList();

        long total;
        if (pageable.getOffset() == 0 && tuples.size() < pageable.getPageSize() && !joinCtx.hasCollectionJoin()) {
            total = tuples.size();
        } else {
            total = executeCount(clientFilter, internalRequest, entityClass, dtoClass, joinCtx.hasCollectionJoin());
        }

        // One batched query per collection for the whole page, inside this read transaction,
        // so lazy state is never touched after the session closes.
        Map<Object, Map<String, Object>> loadedCollections = Map.of();
        if (ownerIdColumn >= 0) {
            List<Object> ownerIds = tuples.stream()
                    .map(tuple -> tuple.get(ownerIdColumn))
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            loadedCollections = collectionLoader.load(collectionPlan, entityClass, ownerIds);
        }

        List<X> content = new ArrayList<>(tuples.size());
        for (Tuple tuple : tuples) {
            Map<String, Object> overrides = ownerIdColumn >= 0
                    ? collectionLoader.valuesFor(collectionPlan, loadedCollections, tuple.get(ownerIdColumn))
                    : Map.of();
            content.add(dtoMapperHelper.mapTupleToDto(tuple, (Class<X>) dtoClass, metas, overrides));
        }

        return new PageImpl<>(content, pageable, total);
    }

    private static int safeOffset(Pageable pageable) {
        long offset = pageable.getOffset();
        if (offset > Integer.MAX_VALUE) {
            throw new QueryRuleException(
                    "Requested page is out of range (offset " + offset + " exceeds Integer.MAX_VALUE)");
        }
        return (int) offset;
    }

    private long executeCount(FilterNode clientFilter, QueryRequest internalRequest, Class<T> entityClass, Class<?> dtoClass, boolean forceDistinct) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
        Root<T> countRoot = countCq.from(entityClass);

        JoinContext countJoinCtx = new JoinContext(specBuilder.maxJoins());

        Specification<T> clientSpec = null;
        if (clientFilter != null) {
            QueryRequest clientOnly = new QueryRequest(clientFilter, List.of(), PageRequest.firstPage());
            clientSpec = specBuilder.buildSpecificationWithJoin(clientOnly, dtoClass, countJoinCtx);
        }

        Specification<T> internalSpec = specBuilder.buildSpecificationWithJoin(internalRequest, null, countJoinCtx);

        Specification<T> finalSpec = andSpecs(clientSpec, internalSpec);

        Predicate predicate = null;
        if (finalSpec != null) {
            predicate = finalSpec.toPredicate(countRoot, countCq, cb);
        }

        boolean needsDistinct = forceDistinct || countJoinCtx.hasCollectionJoin();
        countCq.select(needsDistinct ? cb.countDistinct(countRoot) : cb.count(countRoot));

        if (predicate != null) {
            countCq.where(predicate);
        }

        return entityManager.createQuery(countCq).getSingleResult();
    }

    private static <T> Specification<T> andSpecs(Specification<T> a, Specification<T> b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.and(b);
    }
}
