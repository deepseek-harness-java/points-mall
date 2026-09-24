package cn.xiaofuge.points.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 积分商城助手插件：把 points-app REST API 注册为 DSH Agent 工具 */
public class PointsPlugin extends AbstractHarnessPlugin {

    public static final String PLUGIN_ID = "points-copilot";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public PointsPlugin() { super(PLUGIN_ID); }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new AccountTool(),
                new GoodsListTool(),
                new RedeemTool(),
                new TaskTool(),
                new LedgerTool());
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("points-capabilities", 20, """
                ## 积分商城助手（默认用户 u01 小夏 黄金会员）
                - 用户问"我的积分/余额/等级/快过期的分" → points_account（userId 默认 u01）
                - 用户问"能换什么/有什么商品/XX 分能换啥" → goods_list（可按 category 过滤，hot=true 只看热兑）
                - 用户说"兑换 XX/用积分换" → goods_redeem（先 goods_list 找 goodsId 与库存，报出成本与兑换后余额；余额不够时提示做任务赚分并给差值）
                - 用户问"怎么赚分/做任务/签到" → task_ops（action=list 看任务；action=do 帮用户完成任务）
                - 用户问"积分流水/我的分怎么变的/兑换记录" → points_ledger（action=ledger 流水；action=redeems 兑换记录）
                - 回答要求：
                  1) 报数字：成本、余额、差值、库存；兑换前必须先确认余额够
                  2) 推荐商品时结合用户余额与热度，说清性价比（市场价 vs 积分成本）
                  3) 数据来自工具返回，禁止编造库存与余额
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__")) {
                return PluginHookResult.context("audit: points tool call.");
            }
            return null;
        });
    }

    // ---- HTTP 辅助 ----

    private String get(String path, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path)).GET().build());
    }

    private String post(String path, String jsonBody, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("appBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("POINTS_APP_BASE_URL", "http://127.0.0.1:18090")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return "{\"error\":true,\"status\":" + resp.statusCode() + "}";
            return resp.body();
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String numOr(String v, int dft) {
        try {
            double d = Double.parseDouble(v);
            return v.isEmpty() ? String.valueOf(dft) : (d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d));
        } catch (Exception e) {
            return String.valueOf(dft);
        }
    }

    // ---- 工具 ----

    private class AccountTool extends AbstractTool {
        @Override public String name() { return "points_account"; }
        @Override public String description() {
            return "查积分账户：余额、等级、累计获得/消费、即将过期积分。"
                    + "何时必须调用：用户问我的积分、余额、等级、过期分。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema().prop("userId", stringSchema("用户 ID，默认 u01")).build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String uid = str(args, "userId");
            return ok(get("/api/account?userId=" + (uid.isBlank() ? "u01" : uid), args));
        }
    }

    private class GoodsListTool extends AbstractTool {
        @Override public String name() { return "goods_list"; }
        @Override public String description() {
            return "兑换商品列表：名称/分类/积分成本/库存/已兑量/市场价/是否热兑。"
                    + "何时必须调用：查能换什么、找 goodsId、看某类商品。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("category", stringSchema("可选分类：话费流量/视听会员/餐饮美食/数码周边/居家生活/购物卡券"))
                    .prop("hot", stringSchema("传 true 只看热兑商品"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            StringBuilder path = new StringBuilder("/api/goods?");
            String cat = str(args, "category");
            if (!cat.isBlank()) path.append("category=").append(cat).append('&');
            if ("true".equalsIgnoreCase(str(args, "hot"))) path.append("hot=true&");
            return ok(get(path.toString(), args));
        }
    }

    private class RedeemTool extends AbstractTool {
        @Override public String name() { return "goods_redeem"; }
        @Override public String description() {
            return "积分兑换商品：需要 goodsId（先 goods_list 查），quantity 默认 1。返回兑换码与兑换后余额。"
                    + "何时必须调用：用户明确要兑换。积分不足时不要强行兑换，先告知差值。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("userId", stringSchema("用户 ID，默认 u01"))
                    .prop("goodsId", stringSchema("商品 ID，如 g01"))
                    .prop("quantity", stringSchema("数量，默认 1"))
                    .required("goodsId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String uid = str(args, "userId");
            String body = "{\"userId\":\"" + json(uid.isBlank() ? "u01" : uid)
                    + "\",\"goodsId\":\"" + json(str(args, "goodsId"))
                    + "\",\"quantity\":" + numOr(str(args, "quantity"), 1) + "}";
            return ok(post("/api/redeem", body, args));
        }
    }

    private class TaskTool extends AbstractTool {
        @Override public String name() { return "task_ops"; }
        @Override public String description() {
            return "赚分任务：action=list 查任务列表（奖励/每日上限/今日已完成）；action=do 完成任务（taskId，如 k01 每日签到）。"
                    + "何时必须调用：用户想赚积分、做任务、签到。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("action", stringSchema("list 查询（默认）/ do 完成任务"))
                    .prop("userId", stringSchema("用户 ID，默认 u01"))
                    .prop("taskId", stringSchema("任务 ID（do 时必填），如 k01"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            if ("do".equalsIgnoreCase(str(args, "action"))) {
                String uid = str(args, "userId");
                String body = "{\"userId\":\"" + json(uid.isBlank() ? "u01" : uid)
                        + "\",\"taskId\":\"" + json(str(args, "taskId")) + "\"}";
                return ok(post("/api/task/do", body, args));
            }
            return ok(get("/api/tasks", args));
        }
    }

    private class LedgerTool extends AbstractTool {
        @Override public String name() { return "points_ledger"; }
        @Override public String description() {
            return "积分流水与兑换记录：action=ledger 查加分/扣分明细；action=redeems 查兑换记录（含兑换码）。"
                    + "何时必须调用：用户问分怎么变的、兑换记录、兑换码。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("action", stringSchema("ledger 流水（默认）/ redeems 兑换记录"))
                    .prop("userId", stringSchema("用户 ID，默认 u01"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String uid = str(args, "userId").isBlank() ? "u01" : str(args, "userId");
            String action = str(args, "action");
            return ok(get("/api/" + ("redeems".equalsIgnoreCase(action) ? "redeems?userId=" : "ledger?userId=") + uid, args));
        }
    }
}
