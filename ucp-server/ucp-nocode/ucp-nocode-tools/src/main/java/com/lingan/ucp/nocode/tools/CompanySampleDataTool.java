package com.lingan.ucp.nocode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 公司领域可重复使用的模拟数据工具，只写既有三张实体表。
 *
 * <p>复用正式配置及受管事务；固定批次完整存在时仅验证，部分存在时终止，不覆盖用户修改。 公司、账号、流水和提交前核验处于同一事务，不创建应用或修改对象定义。
 */
public class CompanySampleDataTool {
    private static final String BATCH = "COMPANY_SAMPLE_20260907_V1";
    private static final String NOTE = "模拟数据；批次=" + BATCH + "；不代表真实公司或金融交易";
    private static final LocalDate START = LocalDate.of(2026, 4, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 7);
    private static final List<String> TABLES =
            List.of("biz_company", "biz_company_financial_account", "biz_company_transaction");
    private static final String[] NAMES = {
        "青空科技", "樱桥贸易", "晴海物流", "星野咨询", "若叶设计", "松风制造", "白鹭餐饮", "枫叶商事",
        "远山建设", "朝日教育", "月见传媒", "海原农业", "竹林能源", "水镜软件", "雪原旅行", "虹川电子",
        "花溪食品", "森海健康", "光庭文创", "云川服务"
    };
    private static final String[] CITIES = {"东京都", "大阪府", "神奈川县", "爱知县", "福冈县"};
    private static final String[] BANKS = {"模拟樱花银行", "模拟青空银行", "模拟枫叶银行"};
    private static final String[] PURPOSES = {"经营收支", "税费及日常费用", "备用资金"};
    private static final String[] EXPENSES = {"办公租金", "员工薪酬", "物料采购", "云服务费", "物流运费", "税费缴纳"};
    private static final String[] ACCOUNTS = {"租赁费", "工资薪金", "采购成本", "信息服务费", "运输费", "税金及附加"};

    /** 参数：inspect|seed|verify 结果文件；连接信息始终来自 NocodeToolContext。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("inspect", "seed", "verify").contains(args[0])) {
            throw new IllegalArgumentException("inspect|seed|verify output.json");
        }
        try (var context = NocodeToolContext.open()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.setTimeout(120);
            var report = new LinkedHashMap<String, Object>();
            report.put("batch", BATCH);
            report.put("mode", args[0]);
            report.put("database", jdbc.queryForObject("SELECT current_database()", String.class));
            report.put("before", counts(jdbc));
            report.put(
                    "objects",
                    jdbc.queryForList(
                            """
SELECT id, object_code, current_published_version_no
FROM public.nocode_object WHERE deleted=0
AND object_code IN ('company','company_financial_account','company_transaction')
ORDER BY id
"""));
            if (args[0].equals("inspect")) {
                report.put(
                        "schemas",
                        jdbc.queryForList(
                                """
SELECT o.object_code,v.schema_json::text definition
FROM public.nocode_object o JOIN public.nocode_object_version v
  ON v.object_id=o.id AND v.version_no=o.current_published_version_no
WHERE o.deleted=0 AND v.deleted=0
  AND o.object_code IN ('company','company_financial_account','company_transaction')
ORDER BY o.id
"""));
            }
            if (args[0].equals("seed")) {
                Boolean created =
                        tx.execute(
                                status -> {
                                    jdbc.execute("SET LOCAL lock_timeout='5s'");
                                    // 防止并发写入使批次数量检查与实际提交不一致，不锁其他业务或元数据表。
                                    jdbc.execute(
                                            "LOCK TABLE public.biz_company,"
                                                    + " public.biz_company_financial_account,"
                                                    + " public.biz_company_transaction IN SHARE ROW"
                                                    + " EXCLUSIVE MODE");
                                    check(
                                            jdbc.queryForObject(
                                                            """
SELECT count(*) FROM public.nocode_object WHERE deleted=0
AND object_code IN ('company','company_financial_account','company_transaction')
AND current_published_version_no IS NOT NULL
""",
                                                            Long.class)
                                                    == 3,
                                            "三个对象必须已发布");
                                    var existing = batchCounts(jdbc);
                                    if (existing.stream().anyMatch(n -> n != 0)) {
                                        verify(jdbc);
                                        return false;
                                    }
                                    var baseline = fingerprints(jdbc);
                                    Long actor =
                                            jdbc.queryForObject(
                                                    "SELECT id FROM public.system_users WHERE"
                                                        + " username=? AND deleted=0 AND status=0",
                                                    Long.class,
                                                    "admin");
                                    seed(jdbc, Objects.requireNonNull(actor));
                                    verify(jdbc);
                                    check(baseline.equals(fingerprints(jdbc)), "原有数据或配置发生变化");
                                    report.put("existingDataAndMetadataUnchanged", true);
                                    report.put("actorId", actor);
                                    return true;
                                });
                report.put("created", created);
            }
            if (!args[0].equals("inspect")) {
                // 提交后另开事务重新读取，结果证据不依赖插入过程中的内存计数。
                report.put("verification", tx.execute(status -> verify(jdbc)));
            }
            report.put("after", counts(jdbc));
            report.put("completedAt", OffsetDateTime.now().toString());
            Path output = Path.of(args[1]).toAbsolutePath();
            Files.createDirectories(output.getParent());
            new ObjectMapper()
                    .findAndRegisterModules()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(output.toFile(), report);
            report.remove("schemas");
            System.out.println(new ObjectMapper().writeValueAsString(report));
        }
    }

    private static List<Map<String, Object>> counts(JdbcTemplate jdbc) {
        var result = new ArrayList<Map<String, Object>>();
        for (String table : TABLES) {
            result.add(
                    Map.of(
                            "table",
                            table,
                            "rows",
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public." + table + " WHERE deleted=0",
                                    Long.class)));
        }
        return result;
    }

    private static List<Long> batchCounts(JdbcTemplate jdbc) {
        return List.of(
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.biz_company WHERE notes=?", Long.class, NOTE),
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.biz_company_financial_account WHERE notes=?",
                        Long.class,
                        NOTE),
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.biz_company_transaction WHERE"
                                + " source_batch_no=?",
                        Long.class,
                        BATCH));
    }

    private static List<String> fingerprints(JdbcTemplate jdbc) {
        var values = new ArrayList<String>();
        for (String table : TABLES) {
            String column = table.equals("biz_company_transaction") ? "source_batch_no" : "notes";
            String marker = column.equals("notes") ? NOTE : BATCH;
            values.add(
                    jdbc.queryForObject(
                            "SELECT md5(COALESCE(string_agg(to_jsonb(t)::text, '' ORDER BY id),''))"
                                    + " FROM public."
                                    + table
                                    + " t WHERE "
                                    + column
                                    + " IS DISTINCT FROM ?",
                            String.class,
                            marker));
        }
        for (String table :
                List.of("nocode_object", "nocode_object_version", "nocode_application")) {
            values.add(
                    jdbc.queryForObject(
                            "SELECT md5(COALESCE(string_agg(to_jsonb(t)::text, '' ORDER BY id),''))"
                                    + " FROM public."
                                    + table
                                    + " t",
                            String.class));
        }
        return values;
    }

    private static void seed(JdbcTemplate jdbc, long actor) {
        String audit = Long.toString(actor);
        List<Object[]> flows = new ArrayList<>();
        for (int c = 1; c <= 20; c++) {
            String name = "【模拟】" + NAMES[c - 1] + "株式会社";
            Long company =
                    jdbc.queryForObject(
                            """
INSERT INTO public.biz_company (official_name,short_name,name_kana,name_en,
    corporate_number,legal_representative,directors,registered_capital,capital_currency,
    owner_name,shareholding_structure,established_date,fiscal_month,management_type,
    entrust_type,management_status,postal_code,registered_address,contact_email,manager,
    notes,creator,updater)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
""",
                            Long.class,
                            name,
                            "模拟" + NAMES[c - 1],
                            "サンプルカブシキガイシャ" + c,
                            "Sample Company %02d Co., Ltd.".formatted(c),
                            "%013d".formatted(c),
                            "模拟代表%02d".formatted(c),
                            "模拟董事%02d甲、模拟董事%02d乙".formatted(c, c),
                            BigDecimal.valueOf(10_000_000L + c * 1_000_000L),
                            "JPY",
                            "模拟股东%02d甲".formatted(c),
                            "模拟股东甲60%；模拟股东乙40%",
                            Date.valueOf(LocalDate.of(2014 + c % 10, 1 + c % 12, 1 + c % 25)),
                            1 + (c - 1) % 12,
                            new String[] {"GROUP", "MANAGED", "CUSTOMER_SELF"}[(c - 1) % 3],
                            c % 3 == 0 ? "PARTIAL" : "FULL",
                            "MANAGING",
                            "000-%04d".formatted(c),
                            CITIES[(c - 1) % 5] + "模拟町" + c + "-1（虚构地址）",
                            "company%02d@example.com".formatted(c),
                            actor,
                            NOTE,
                            audit,
                            audit);
            long[] accounts = new long[3];
            BigDecimal[] balances = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            for (int a = 0; a < 3; a++) {
                accounts[a] =
                        jdbc.queryForObject(
                                """
INSERT INTO public.biz_company_financial_account (company_id,account_name,
    bank_name,branch_name,account_type,account_number,account_holder,currency,
    purpose,is_primary,account_status,manager,sync_method,notes,creator,updater)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
""",
                                Long.class,
                                company,
                                "模拟" + NAMES[c - 1] + "·" + PURPOSES[a],
                                BANKS[a],
                                CITIES[(c - 1) % 5] + "模拟支店",
                                a == 2 ? "当座" : "普通",
                                "%07d".formatted(c * 10 + a + 1),
                                name,
                                "JPY",
                                PURPOSES[a],
                                a == 0,
                                "ACTIVE",
                                actor,
                                "MANUAL",
                                NOTE,
                                audit,
                                audit);
            }
            for (int i = 0; i < 200; i++) {
                int a = i % 3;
                int expense = (i + c) % EXPENSES.length;
                boolean opening = i < 3;
                boolean income = opening || i % 5 == 0 || i % 5 == 2;
                BigDecimal amount =
                        BigDecimal.valueOf(
                                        opening
                                                ? 5_000_000L + c * 100_000L + a * 50_000L
                                                : income
                                                        ? 100_000L
                                                                + Math.floorMod(
                                                                        c * 7919L + i * 3571L,
                                                                        400_000L)
                                                        : 5_000L
                                                                + Math.floorMod(
                                                                        c * 1297L + i * 2137L,
                                                                        90_000L))
                                .setScale(2);
                balances[a] = balances[a].add(income ? amount : amount.negate());
                LocalDate day = START.plusDays(i * (END.toEpochDay() - START.toEpochDay()) / 199);
                String category = opening ? "期初资金存入" : income ? "客户回款" : EXPENSES[expense];
                String counterparty =
                        opening
                                ? "模拟出资方%02d".formatted(c)
                                : (income ? "模拟客户" : "模拟供应商") + "%02d".formatted(1 + (i + c) % 30);
                flows.add(
                        new Object[] {
                            accounts[a],
                            "【模拟】" + category + "·%02d-%03d".formatted(c, i + 1),
                            Date.valueOf(day),
                            Date.valueOf(day),
                            income ? "INCOME" : "EXPENSE",
                            amount,
                            "JPY",
                            balances[a],
                            counterparty,
                            counterparty,
                            "MANUAL",
                            BATCH,
                            i + 1,
                            hash(BATCH + ":" + c + ":" + (i + 1)),
                            "UNPROCESSED",
                            category,
                            opening ? "实收资本" : income ? "主营业务收入" : ACCOUNTS[expense],
                            "未对账",
                            false,
                            NOTE,
                            audit,
                            audit
                        });
            }
        }
        jdbc.batchUpdate(
                """
INSERT INTO public.biz_company_transaction (account_id,summary,transaction_date,posting_date,
    direction,amount,currency,balance,counterparty,counterparty_normalized,source_type,
    source_batch_no,source_row_no,source_hash,recognition_status,direction_type,account_suggestion,
    reconciliation_status,is_locked,notes,creator,updater)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
                flows);
    }

    private static Map<String, Object> verify(JdbcTemplate jdbc) {
        check(
                batchCounts(jdbc).equals(List.of(20L, 60L, 4000L)),
                "批次应为20家公司、60个账号、4000条流水，部分批次不自动覆盖");
        for (String table : TABLES) {
            String markerColumn =
                    table.equals("biz_company_transaction") ? "source_batch_no" : "notes";
            Long invalid =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM public."
                                    + table
                                    + " WHERE "
                                    + markerColumn
                                    + "=? AND (deleted<>0 OR creator IS NULL OR creator='' OR"
                                    + " updater IS NULL OR updater='' OR create_time IS NULL OR"
                                    + " update_time IS NULL)",
                            Long.class,
                            markerColumn.equals("notes") ? NOTE : BATCH);
            check(invalid == 0, table + "存在已删除记录或缺少审计信息");
        }
        var companies =
                jdbc.queryForList(
                        """
SELECT c.id,c.official_name,
    (SELECT count(*) FROM public.biz_company_financial_account a WHERE a.company_id=c.id AND a.deleted=0) accounts,
    (SELECT count(*) FROM public.biz_company_financial_account a WHERE a.company_id=c.id AND a.deleted=0 AND a.is_primary) primary_accounts,
    (SELECT count(*) FROM public.biz_company_transaction t JOIN public.biz_company_financial_account a ON a.id=t.account_id
        WHERE a.company_id=c.id AND t.deleted=0) transactions
FROM public.biz_company c WHERE c.notes=? ORDER BY c.id
""",
                        NOTE);
        check(companies.size() == 20, "模拟公司数错误");
        for (var company : companies) {
            check(
                    ((Number) company.get("accounts")).intValue() == 3
                            && ((Number) company.get("primary_accounts")).intValue() == 1
                            && ((Number) company.get("transactions")).intValue() == 200,
                    "每家公司必须3个账号、1个主账号、200条流水");
        }
        check(
                jdbc.queryForObject(
                                """
SELECT count(*) FROM public.biz_company_financial_account a LEFT JOIN public.biz_company c ON c.id=a.company_id
WHERE a.notes=? AND (c.notes IS DISTINCT FROM ? OR c.deleted<>0)
""",
                                Long.class,
                                NOTE,
                                NOTE)
                        == 0,
                "账号归属错误");
        check(
                jdbc.queryForObject(
                                """
SELECT count(*) FROM public.biz_company_transaction t LEFT JOIN public.biz_company_financial_account a ON a.id=t.account_id
WHERE t.source_batch_no=? AND (a.notes IS DISTINCT FROM ? OR a.deleted<>0 OR t.currency IS DISTINCT FROM a.currency)
""",
                                Long.class,
                                BATCH,
                                NOTE)
                        == 0,
                "流水归属或币种错误");
        var ledger =
                jdbc.queryForMap(
                        """
WITH ledger AS (
    SELECT t.*,sum(CASE WHEN direction='INCOME' THEN amount ELSE -amount END)
        OVER (PARTITION BY account_id ORDER BY transaction_date,id ROWS UNBOUNDED PRECEDING) expected_balance
    FROM public.biz_company_transaction t WHERE source_batch_no=?
) SELECT count(*) FILTER (WHERE balance IS DISTINCT FROM expected_balance) balance_errors,
    count(*) FILTER (WHERE amount IS NULL OR amount<=0 OR direction IS NULL OR direction NOT IN ('INCOME','EXPENSE')) invalid_amounts,
    count(DISTINCT source_hash) unique_hashes, min(transaction_date) first_date,max(transaction_date) last_date,
    count(*) FILTER (WHERE direction='INCOME') income_rows,
    count(*) FILTER (WHERE direction='EXPENSE') expense_rows
FROM ledger
""",
                        BATCH);
        check(((Number) ledger.get("balance_errors")).longValue() == 0, "账号余额不连续");
        check(((Number) ledger.get("invalid_amounts")).longValue() == 0, "金额或方向错误");
        check(((Number) ledger.get("unique_hashes")).longValue() == 4000, "流水存在重复来源哈希");
        var distribution =
                jdbc.queryForList(
                        """
SELECT transactions,count(*) accounts FROM (
    SELECT account_id,count(*) transactions FROM public.biz_company_transaction
    WHERE source_batch_no=? GROUP BY account_id
) t GROUP BY transactions ORDER BY transactions
""",
                        BATCH);
        check(
                distribution.size() == 2
                        && ((Number) distribution.get(0).get("transactions")).intValue() == 66
                        && ((Number) distribution.get(0).get("accounts")).intValue() == 20
                        && ((Number) distribution.get(1).get("transactions")).intValue() == 67
                        && ((Number) distribution.get(1).get("accounts")).intValue() == 40,
                "账号流水分配错误");
        return Map.of(
                "companies",
                companies,
                "ledger",
                ledger,
                "accountDistribution",
                distribution,
                "passed",
                true);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
