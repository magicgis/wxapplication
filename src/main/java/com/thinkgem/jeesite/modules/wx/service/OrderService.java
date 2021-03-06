/**
 * Copyright &copy; 2012-2016 <a href="https://github.com/thinkgem/jeesite">JeeSite</a> All rights reserved.
 */
package com.thinkgem.jeesite.modules.wx.service;

import com.thinkgem.jeesite.common.service.CrudService;
import com.thinkgem.jeesite.modules.wx.constant.OrderState;
import com.thinkgem.jeesite.modules.wx.constant.WechatConstant;
import com.thinkgem.jeesite.modules.wx.entity.*;
import com.thinkgem.jeesite.modules.wx.entity.vo.*;
import com.thinkgem.jeesite.modules.wx.utils.*;
import com.thinkgem.jeesite.modules.wx.dao.OrderDao;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.*;

import static com.thinkgem.jeesite.modules.wx.constant.WechatConstant.*;

/**
 * 菜品Service
 *
 * @author tgp
 * @version 2018-06-04
 */
@Service
public class OrderService extends CrudService<OrderDao, Order> {

    private final static Logger logger = LoggerFactory.getLogger(OrderService.class);

    private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private OrderDao orderDao;

    @Autowired
    private FoodService foodService;

    @Autowired
    private StoreService storeService;

    @Autowired
    private FoodCategoryService foodCategoryService;

    /**
     * 创建订单
     *
     * @param postOrder
     * @return
     */
    @Transactional
    public OrderVo addOrder(PostOrder postOrder) {
        //查找本店库里所有菜品id
        List<Food> foodList = foodService.listAllFood(postOrder.getStoreId());
        //查询该店的所有分类信息
        List<FoodCategory> foodCategoryList = foodCategoryService.listAllFoodCategory(postOrder.getStoreId());
        Map<String, String> categoryMap = new HashMap<>();
        for (FoodCategory foodCategory : foodCategoryList) {
            categoryMap.put(foodCategory.getId(), foodCategory.getName());
        }

        //用户点菜菜品id和数量map
        Map<String, Integer> foodMap = postOrder.getFoodMap();
        //用户点菜菜品id列表
        Set<String> foodIds = foodMap.keySet();
        //存放用户点菜对应的菜品信息
        List<Food> list2 = new ArrayList<>();
        //用户提交的菜品id数量
        int tag = foodIds.size();
        //用户点菜的总金额
        double amount = 0;

        //找到用户提交的id对应的菜品信息
        for (String id : foodIds) {
            for (Food food : foodList) {
                if (food.getId().equals(id)) {
                    list2.add(food);
                    //用户提交的菜品id数量，找到一个就减去一个
                    tag--;
                    //累计金额
                    int count = foodMap.get(food.getId());
                    amount += (food.getPrice().doubleValue()) * count;
                    break;
                }
            }
        }
        //用户提交的菜品id数量，存在没有找到的菜品，下单失败
        if (tag != 0) {
            throw new IllegalArgumentException("存在不存在的菜品,下单失败");
        }


        //1.创建订单
        Order order = new Order();
        String id = UUIDUtils.timeBasedStr();
        order.setId(id);
        order.setAmount(amount);
        order.setCreateAt(new Date());
        DateTime currentDate = new DateTime();
        Date date1 = currentDate.withTimeAtStartOfDay().toDate();
        order.setCreateDay(date1);
        Date date2 = currentDate.dayOfMonth().withMinimumValue().withTimeAtStartOfDay().toDate();
        order.setCreateMonth(date2);
        order.setCustomerName(postOrder.getCustomerName());
        order.setCustomerWxId(postOrder.getCustomerWxId());
        order.setStoreId(postOrder.getStoreId());
        order.setState(OrderState.UNPAID);
        order.setTableNum(postOrder.getTableNum());

        //2.创建订单详情
        List<Order2Food> order2Foods = new ArrayList<>();
        for (Food food : list2) {
            Order2Food order2Food = new Order2Food();
            order2Food.setOrderId(id);
            order2Food.setFoodId(food.getId());
            order2Food.setFoodName(food.getName());
            order2Food.setFoodPicture(food.getPicture());
            order2Food.setFoodCount(foodMap.get(food.getId()));
            order2Food.setFoodPrice(food.getPrice().doubleValue());
            order2Food.setFoodCategoryId(food.getCategoryId());
            order2Food.setFoodCategoryName(categoryMap.get(food.getCategoryId()));
            order2Foods.add(order2Food);
        }
        String foodDetail = JsonUtils.List2Str(order2Foods);
        order.setFoodDetail(foodDetail);

        String nonceStr = WechatConstant.nonce_str;
        String openId = postOrder.getCustomerWxId();
        int totalFee = (int) (amount * 10 * 10);
        //签名
        String stringA =
            "appid=" + appid +
                "&" + "body=" + body +
                "&" + "mch_id=" + mch_id +
                "&" + "nonce_str=" + nonceStr +
                "&" + "notify_url=" + notify_url +
                "&" + "openid=" + openId +
                "&" + "out_trade_no=" + id +
                "&" + "spbill_create_ip=" + spbill_create_ip +
                "&" + "total_fee=" + totalFee +
                "&" + "trade_type=" + trade_type;

        String SignTemp = stringA + "&key=" + key;
        String sign = MD5Util.md5(SignTemp).toUpperCase();
        //-----------------------------调用微信统一下单api----------------------------
        //https://pay.weixin.qq.com/wiki/doc/api/wxa/wxa_api.php?chapter=9_1&index=1
        PostWxOrder postWxOrder = new PostWxOrder(sign, id, totalFee, openId);
        String x = HttpUtils.post("https://api.mch.weixin.qq.com/pay/unifiedorder", postWxOrder);
        OrderVo vo = HttpUtils.xmlToBean(OrderVo.class, x);
        if ("SUCCESS".equals(vo.getReturn_code()) && "SUCCESS".equals(vo.getResult_code())) {
            orderDao.addOrder(order);
        }
        //再次签名，便于小程序去调用支付接口
        String timeStamp = (System.currentTimeMillis() / 1000) + "";
        //拼接签名需要的参数
        String stringB =
            "appId=" + appid + "&nonceStr=" +
                nonceStr + "&package=prepay_id=" + vo.getPrepay_id()
                + "&signType=MD5&timeStamp=" + timeStamp;
        String SignTempB = stringB + "&key=" + key;
        String signB = MD5Util.md5(SignTempB).toUpperCase();
        vo.setSign(signB);
        vo.setNonce_str(nonceStr);
        vo.setTimeStamp(timeStamp);
        // 调用小票打印接口
        print(order);
        return vo;

        //3. 增加菜品的销量
       // return true;

    }

    /**
     * 小票打印
     */
    public void print(Order order) {
/*        StringBuffer requestUrl = new StringBuffer("http://localhost:8080/print"); // TODO 你们可以自行修改
        requestUrl.append("?table=").append(order.getTableNum());
        Store store = storeService.findStoreById(order.getStoreId());
        if (null != store) {
            requestUrl.append("&shopName=").append(store.getName());
        }
        requestUrl.append("&orderId=").append(order.getId());
        requestUrl.append("&time=").append(sdf.format(order.getCreateAt()));
        requestUrl.append("&price=").append(order.getAmount());
        requestUrl.append("&data=").append(order.getFoodDetail());

        HttpUtil.doGet(requestUrl.toString());*/
    }

    /**
     * 根据点餐的用户wx_id获取订单记录
     *
     * @return
     */
    public List<OrderDetail> findOrderByWx_id(String storeId, String wxId, Integer pageSize, Integer pageNo) {
        if (StringUtils.isEmpty(storeId)) {
            throw new IllegalArgumentException("店铺id不可为空");
        }
        if ((null != pageSize && pageSize < 0) || (null != pageNo && pageNo < 0)) {
            logger.info("分页查询用户订单信息失败，pageSize和pageNo都不能小于0！");
            return new LinkedList<>();
        }
        Store store = storeService.findStoreById(storeId);
        if (store == null) {
            throw new IllegalArgumentException("店铺不存在");
        }
        int limit = pageSize;
        int offset = pageNo == 1 ? 0 : pageSize * (pageNo - 1);
        return orderDao.findOrderByWx_id(storeId, wxId, limit, offset);
    }


    /**
     * 查询单个订单
     *
     * @return
     */
    public OrderDetail findById(String orderId) {
        return orderDao.findById(orderId);
    }


    /**
     * 更新订单状态为已支付
     *
     * @return
     */
    @Transactional
    public int updateOrderState(String orderId) {
        if (StringUtils.isEmpty(orderId)) {
            throw new IllegalArgumentException("orderId不可为空");
        }
        OrderDetail orderDetail = findById(orderId);
        if (orderDetail == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        logger.info("update order to paid");
        return orderDao.updateState(orderDetail.getId());
    }

    /**
     * 查询店铺总营业额
     * @param order
     * @return
     */
    public List<StoreOrderTotalAmountVo> findStoreTotalAmount(Order order) {
        return orderDao.findStoreTotalAmount(order);
    }

    /**
     * 查询店铺详细的业绩总营业额
     * @param order
     * @return
     */
    public List<StoreOrderTotalAmountVo> findStoreTotalDetailAmount(Order order) {
        return orderDao.findStoreTotalDetailAmount(order);
    }

}