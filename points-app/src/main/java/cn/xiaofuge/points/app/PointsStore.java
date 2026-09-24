package cn.xiaofuge.points.app;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** 积分商城内存数据层：账户、商品、兑换、赚分任务、积分流水 */
@Component
public class PointsStore {

    public record Account(String userId, String name, String tier, int balance,
                          int totalEarned, int totalSpent, int expiringSoon) {}

    public record Goods(String id, String name, String icon, String category,
                        int cost, int stock, int exchanged, double marketPrice, boolean hot) {}

    public record Redeem(String id, String userId, String goodsId, String goodsName,
                         int cost, String time, String status, String code) {}

    public record Task(String id, String name, String icon, int reward, int dailyLimit,
                       int doneToday, String rule) {}

    public final Map<String, Account> accounts = new ConcurrentHashMap<>();
    public final Map<String, Goods> goodsMap = new ConcurrentHashMap<>();
    public final Map<String, Redeem> redeems = new ConcurrentHashMap<>();
    public final Map<String, Task> tasks = new ConcurrentHashMap<>();
    /** 积分流水：userId -> [(time, delta, reason)] */
    public final Map<String, List<Map<String, Object>>> ledger = new ConcurrentHashMap<>();
    private final AtomicLong redeemGen = new AtomicLong(1000);

    @PostConstruct
    public void init() {
        account(new Account("u01", "小夏", "黄金会员", 12650, 42300, 29650, 800));
        account(new Account("u02", "阿澈", "白银会员", 3200, 9800, 6600, 0));
        account(new Account("u03", "mia", "铂金会员", 28900, 88100, 59200, 2000));

        goods(new Goods("g01", "10 元话费直充", "📱", "话费流量", 1000, 500, 2314, 10.0, true));
        goods(new Goods("g02", "视频月卡", "🎬", "视听会员", 1500, 300, 1876, 25.0, true));
        goods(new Goods("g03", "咖啡中杯券", "☕", "餐饮美食", 2000, 80, 642, 32.0, false));
        goods(new Goods("g04", "蓝牙耳机", "🎧", "数码周边", 19900, 12, 89, 299.0, true));
        goods(new Goods("g05", "保温杯", "🍵", "居家生活", 6800, 45, 217, 129.0, false));
        goods(new Goods("g06", "20 元外卖红包", "🍜", "餐饮美食", 1800, 1000, 3102, 20.0, true));
        goods(new Goods("g07", "视频年卡", "📺", "视听会员", 16800, 6, 31, 258.0, false));
        goods(new Goods("g08", "京东 E 卡 50 元", "🎁", "购物卡券", 5100, 200, 456, 50.0, false));

        task(new Task("k01", "每日签到", "📅", 20, 1, 1, "每天首次签到 +20"));
        task(new Task("k02", "浏览商品 3 分钟", "👀", 15, 3, 2, "单次浏览满 3 分钟 +15"));
        task(new Task("k03", "完成一笔订单评价", "✍️", 50, 2, 0, "订单评价带图 +50"));
        task(new Task("k04", "邀请好友注册", "🤝", 300, 5, 1, "好友完成首单 +300"));
        task(new Task("k05", "连续签到 7 天冲刺", "🔥", 150, 1, 0, "月内累计 7 天额外 +150"));

        ledger("u01", "2026-09-20 10:12", 300, "邀请好友注册");
        ledger("u01", "2026-09-21 09:00", 20, "每日签到");
        ledger("u01", "2026-09-21 15:40", -2000, "兑换：咖啡中杯券");
        ledger("u01", "2026-09-22 09:01", 20, "每日签到");
        ledger("u01", "2026-09-23 18:22", -1500, "兑换：视频月卡");
        ledger("u01", "2026-09-24 09:02", 20, "每日签到");
    }

    private void account(Account a) { accounts.put(a.userId(), a); }
    private void goods(Goods g) { goodsMap.put(g.id(), g); }
    private void task(Task t) { tasks.put(t.id(), t); }
    private void ledger(String userId, String time, int delta, String reason) {
        ledger.computeIfAbsent(userId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(Map.of("time", time, "delta", delta, "reason", reason));
    }

    /** 账户信息 */
    public Account account(String userId) { return accounts.get(userId); }

    /** 商品列表：按分类过滤，hot 优先 + 兑换量降序 */
    public List<Goods> goodsList(String category, boolean onlyHot) {
        return goodsMap.values().stream()
                .filter(g -> category == null || category.isBlank() || g.category().equals(category))
                .filter(g -> !onlyHot || g.hot())
                .sorted(Comparator.comparing(Goods::hot).reversed().thenComparing(Comparator.comparingInt(Goods::exchanged).reversed()))
                .collect(Collectors.toList());
    }

    /** 兑换：余额/库存校验，扣减并写流水 */
    public Map<String, Object> redeem(String userId, String goodsId, int quantity) {
        Account a = accounts.get(userId);
        Goods g = goodsMap.get(goodsId);
        if (a == null) return Map.of("ok", false, "message", "用户不存在: " + userId);
        if (g == null) return Map.of("ok", false, "message", "商品不存在: " + goodsId);
        int qty = Math.max(1, quantity);
        int cost = g.cost() * qty;
        if (g.stock() < qty) return Map.of("ok", false, "message", "库存不足，仅剩 " + g.stock());
        if (a.balance() < cost) return Map.of("ok", false,
                "message", "积分不足：需 " + cost + "，当前 " + a.balance() + "，还差 " + (cost - a.balance()));
        Account na = new Account(a.userId(), a.name(), a.tier(), a.balance() - cost,
                a.totalEarned(), a.totalSpent() + cost, a.expiringSoon());
        accounts.put(userId, na);
        Goods ng = new Goods(g.id(), g.name(), g.icon(), g.category(), g.cost(),
                g.stock() - qty, g.exchanged() + qty, g.marketPrice(), g.hot());
        goodsMap.put(goodsId, ng);
        String id = "r" + redeemGen.incrementAndGet();
        String code = "PT" + id.substring(1) + String.format("%04d", new Random().nextInt(10000));
        Redeem r = new Redeem(id, userId, goodsId, g.name() + "×" + qty, cost,
                "2026-09-24 09:15", "兑换成功", code);
        redeems.put(id, r);
        ledger(userId, "2026-09-24 09:15", -cost, "兑换：" + ng.name());
        Map<String, Object> m = new HashMap<>();
        m.put("ok", true); m.put("redeemId", id); m.put("goods", ng.name());
        m.put("cost", cost); m.put("code", code);
        m.put("balanceAfter", na.balance());
        return m;
    }

    /** 兑换记录 */
    public List<Redeem> redeemHistory(String userId) {
        return redeems.values().stream()
                .filter(r -> userId == null || userId.isBlank() || r.userId().equals(userId))
                .sorted(Comparator.comparing(Redeem::time).reversed())
                .collect(Collectors.toList());
    }

    /** 赚分任务列表 */
    public List<Task> taskList() {
        return tasks.values().stream().sorted(Comparator.comparing(Task::id)).collect(Collectors.toList());
    }

    /** 完成任务 */
    public Map<String, Object> doTask(String userId, String taskId) {
        Account a = accounts.get(userId);
        Task t = tasks.get(taskId);
        if (a == null) return Map.of("ok", false, "message", "用户不存在");
        if (t == null) return Map.of("ok", false, "message", "任务不存在: " + taskId);
        if (t.doneToday() >= t.dailyLimit()) return Map.of("ok", false, "message", "「" + t.name() + "」今日次数已用完（" + t.dailyLimit() + " 次）");
        Task nt = new Task(t.id(), t.name(), t.icon(), t.reward(), t.dailyLimit(),
                t.doneToday() + 1, t.rule());
        tasks.put(taskId, nt);
        Account na = new Account(a.userId(), a.name(), a.tier(), a.balance() + t.reward(),
                a.totalEarned() + t.reward(), a.totalSpent(), a.expiringSoon());
        accounts.put(userId, na);
        ledger(userId, "2026-09-24 09:15", t.reward(), "任务：" + t.name());
        return Map.of("ok", true, "task", t.name(), "reward", t.reward(), "balanceAfter", na.balance());
    }

    /** 流水（最近在前） */
    public List<Map<String, Object>> ledgerOf(String userId) {
        List<Map<String, Object>> list = ledger.getOrDefault(userId, new ArrayList<>());
        List<Map<String, Object>> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    /** 商城概览 */
    public Map<String, Object> overview() {
        Map<String, Object> m = new HashMap<>();
        m.put("userCount", accounts.size());
        m.put("goodsCount", goodsMap.size());
        m.put("redeemCount", redeems.size() + 9865);
        m.put("todayRedeems", redeems.size());
        m.put("hotGoods", goodsList(null, true).stream().map(Goods::name).limit(3).collect(Collectors.toList()));
        m.put("date", "2026-09-24");
        return m;
    }
}
