package cn.xiaofuge.points.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 积分商城 REST API */
@RestController
public class PointsController {

    private final PointsStore store;

    public PointsController(PointsStore store) { this.store = store; }

    /** 账户信息 */
    @GetMapping("/api/account")
    public Map<String, Object> account(@RequestParam String userId) {
        PointsStore.Account a = store.account(userId);
        return a == null ? Map.of("code", 404, "message", "用户不存在")
                : Map.of("code", 0, "data", a);
    }

    /** 商品列表 */
    @GetMapping("/api/goods")
    public Map<String, Object> goods(@RequestParam(required = false) String category,
                                     @RequestParam(required = false, defaultValue = "false") boolean hot) {
        return Map.of("code", 0, "data", store.goodsList(category, hot));
    }

    /** 兑换 */
    @PostMapping("/api/redeem")
    public Map<String, Object> redeem(@RequestBody Map<String, Object> body) {
        return Map.of("code", 0, "data", store.redeem(
                str(body.get("userId")), str(body.get("goodsId")),
                (int) dbl(body.get("quantity"), 1)));
    }

    /** 兑换记录 */
    @GetMapping("/api/redeems")
    public Map<String, Object> redeems(@RequestParam(required = false) String userId) {
        return Map.of("code", 0, "data", store.redeemHistory(userId));
    }

    /** 赚分任务 */
    @GetMapping("/api/tasks")
    public Map<String, Object> tasks() {
        return Map.of("code", 0, "data", store.taskList());
    }

    /** 完成任务 */
    @PostMapping("/api/task/do")
    public Map<String, Object> doTask(@RequestBody Map<String, Object> body) {
        return Map.of("code", 0, "data", store.doTask(str(body.get("userId")), str(body.get("taskId"))));
    }

    /** 积分流水 */
    @GetMapping("/api/ledger")
    public Map<String, Object> ledger(@RequestParam String userId) {
        return Map.of("code", 0, "data", store.ledgerOf(userId));
    }

    /** 概览 */
    @GetMapping("/api/overview")
    public Map<String, Object> overview() {
        return Map.of("code", 0, "data", store.overview());
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
    private static double dbl(Object v, double dft) {
        try { return v == null ? dft : Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return dft; }
    }
}
