package cn.itcast.hotel.service.impl;

import cn.itcast.hotel.mapper.HotelMapper;
import cn.itcast.hotel.pojo.Hotel;
import cn.itcast.hotel.pojo.HotelDoc;
import cn.itcast.hotel.pojo.PageResult;
import cn.itcast.hotel.pojo.RequestParams;
import cn.itcast.hotel.service.IHotelService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.DistanceUnit;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.TermsAggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class HotelServiceImpl extends ServiceImpl<HotelMapper, Hotel> implements IHotelService {
    @Resource(name = "client")
    private ElasticsearchClient client;


    /**
     * 根据条件过滤查询
     *
     * @param params 前端传递的分页查询参数
     * @return 返回分页结果
     * @throws IOException 抛出异常
     */
    @Override
    public PageResult searchAndFilter(RequestParams params) throws IOException {
        //获取布尔查询构造器
        BoolQuery.Builder boolBuilder = getBoolQuery(params);
        //构建出布尔查询
        BoolQuery boolQuery = boolBuilder.build();
        //构建出排序设置
        List<SortOptions> sortOption = getSortOption(params);
        //算分控制构造器
        FunctionScoreQuery.Builder fBuilder = QueryBuilders.functionScore();
        //构建出算分查询
        FunctionScoreQuery functionScoreQuery = fBuilder.query(boolQuery._toQuery())
                .functions(f->f.filter(t->t.term(tt->tt.field("isAD").value(true)))
                        .weight(10D)).boostMode(FunctionBoostMode.Sum).build();
        SearchRequest searchRequest = new SearchRequest.Builder().index("hotel")
                .query(functionScoreQuery._toQuery())
                .sort(sortOption)
                .from(params.getPage() - 1).size(params.getSize()).build();
        SearchResponse<HotelDoc> response = client.search(searchRequest, HotelDoc.class);
        return getPageResult(response);
    }

    /**
     * 城市,星级,品牌,价格动态变化
     * @return
     */
    @Override
    public Map<String, List<String>> filters(RequestParams params) {
        //获取布尔查询构造器
        BoolQuery.Builder boolBuilder = getBoolQuery(params);
        //构建出布尔查询
        BoolQuery boolQuery = boolBuilder.build();
        //聚合函数查询
        SearchResponse<HotelDoc> response = null;
        try {
            response = client.search(s -> s
                    .index("hotel")
                    .size(0)
                    .query(boolQuery._toQuery())
                    .aggregations(getAggregations(params)), HotelDoc.class);
        } catch (IOException e) {
            throw new RuntimeException("出错");
        }
        //封装查询结果
        Map<String, List<String>> result = new HashMap<>();
        Map<String, Aggregate> aggregations = response.aggregations();
        result.put("city", getList(aggregations,"city_agg"));
        result.put("brand", getList(aggregations,"brand_agg"));
        result.put("starName", getList(aggregations,"star_agg"));
        return result;
    }

    /**
     * 封装聚合后的结果
     * @param aggregations 聚合函数
     * @param bucketName 桶名字
     * @return  返回结果
     */
    private List<String> getList(Map<String, Aggregate> aggregations, String bucketName){
        List<StringTermsBucket> buckets = aggregations.get(bucketName).sterms().buckets().array();
        List<String > list = new ArrayList<>();
        for (StringTermsBucket bucket : buckets) {
            list.add(bucket.key());
        }
        return list;
    }
    /**
     * 设置聚合Aggregation函数条件
     * @return 返回设置条件
     */
    private Map<String, Aggregation> getAggregations(RequestParams params){
        Map<String, Aggregation> map = new HashMap<>();
        TermsAggregation brandAggregation = new TermsAggregation.Builder()
                .size(100).field("brand").build();
        TermsAggregation cityAggregation = new TermsAggregation.Builder()
                .size(100).field("city").build();
        TermsAggregation starAggregation = new TermsAggregation.Builder()
                .size(100).field("starName").build();
        map.put("brand_agg",new Aggregation(brandAggregation));
        map.put("star_agg",new Aggregation(starAggregation));
        map.put("city_agg",new Aggregation(cityAggregation));
        return map;
    }


    /**
     * 构造出排序设置
     *
     * @param params 前端传递参数
     * @return 返回排序设置
     */
    private List<SortOptions> getSortOption(RequestParams params) {
        List<SortOptions> sortOptionsList = new ArrayList<>();
        //排序选项构造器
        SortOptions.Builder sort1 = new SortOptions.Builder();
        SortOptions.Builder sort = new SortOptions.Builder();
        SortOptions sortOptions = null;
        if ("default".equals(params.getSortBy())) {
            //广告优先
            sortOptions = sort.field(f -> f.field("isAD").order(SortOrder.Desc)).build();
        } else {
            //其余按照评价跟价格升序排序
            sortOptions = sort.field(f -> f.field(params.getSortBy()).order(SortOrder.Asc)).build();
        }
        sortOptionsList.add(sortOptions);
        params.setLocation("31.034661,121.612282");
        //地理位置排序
        if (!"".equals(params.getLocation()) && params.getLocation() != null) {
            SortOptions options = sort1.geoDistance(g -> g.location(l->l.text(params.getLocation())).field("location")
                    .unit(DistanceUnit.Kilometers).order(SortOrder.Asc)).build();
            sortOptionsList.add(options);
        }
        return sortOptionsList;
    }

    /**
     * 获取布尔查询构造器(包含过滤条件)
     *
     * @param params 前端传递参数
     * @return 返回构造器
     */
    private BoolQuery.Builder getBoolQuery(RequestParams params) {
        //注意一个构造器只能build一次,有几个条件,则需要创建几个build对象
        //布尔查询构造器
        BoolQuery.Builder boolBuilder = QueryBuilders.bool();
        //范围匹配查询构造器
        RangeQuery.Builder rangeBuilder = QueryBuilders.range();
        // 过滤城市条件
        if (params.getCity() != null && !params.getCity().equals("")) {
            //精确匹配查询构造器
            TermQuery.Builder termBuilder = QueryBuilders.term();
            TermQuery termQuery = termBuilder.field("city").value(params.getCity()).build();
            boolBuilder.filter(termQuery._toQuery());
        }
        // 过滤品牌条件
        if (params.getBrand() != null && !params.getBrand().equals("")) {
            TermQuery.Builder termBuilder = QueryBuilders.term();
            TermQuery termQuery = termBuilder.field("brand").value(params.getBrand()).build();
            boolBuilder.filter(termQuery._toQuery());
        }
        // 过滤星级条件
        if (params.getStarName() != null && !params.getStarName().equals("")) {
            TermQuery.Builder termBuilder = QueryBuilders.term();
            TermQuery termQuery = termBuilder.field("startName").value(params.getStarName()).build();
            boolBuilder.filter(termQuery._toQuery());
        }
        // 过滤价格条件
        if (params.getMinPrice() != null && params.getMaxPrice() != null) {
            RangeQuery rangeQuery = rangeBuilder.field("price").gte(JsonData.of(params.getMinPrice()))
                    .lte(JsonData.of(params.getMaxPrice())).build();
            boolBuilder.filter(rangeQuery._toQuery());
        }
        //搜索框查询是模糊查询,构造默许查询构造器
        WildcardQuery.Builder wildBuilder = QueryBuilders.wildcard();
        if (params.getKey() != null || !"".equals(params.getKey())) {
            String str = "*" + params.getKey() + "*";
            //构造出模糊查询
            WildcardQuery wildcardQuery = wildBuilder.field("all").wildcard(str).build();
            //用must整合到布尔构造器
            boolBuilder.must(wildcardQuery._toQuery());
        }
        return boolBuilder;
    }

    /**
     * 封装需要的数据到结果集
     *
     * @param response 查询出来的原始值
     * @return
     */
    private PageResult getPageResult(SearchResponse<HotelDoc> response) {
        PageResult result = new PageResult();
        if (response.hits().total() != null) {
            result.setTotal(response.hits().total().value());
        }
        List<Hit<HotelDoc>> hits = response.hits().hits();
        List<HotelDoc> list = new ArrayList<>();
        for (Hit<HotelDoc> hit : hits) {
            HotelDoc hotelDoc = new HotelDoc();
            assert hit.source() != null;
            BeanUtils.copyProperties(hit.source(),hotelDoc);
            List<String> sort = hit.sort();
            if(sort.size()>0){
                double d = Double.parseDouble(sort.get(1));
                BigDecimal distance = new BigDecimal(d);
                distance = distance.setScale(2, RoundingMode.HALF_UP);
                hotelDoc.setDistance(distance);
            }
            list.add(hotelDoc);
        }
        result.setHotels(list);
        return result;
    }
}
