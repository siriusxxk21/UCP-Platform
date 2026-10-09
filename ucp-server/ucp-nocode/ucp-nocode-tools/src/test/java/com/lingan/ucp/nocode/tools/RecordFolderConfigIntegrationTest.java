package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.RecordFolderFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;

import org.junit.jupiter.api.*;

import java.util.*;

/**
 * 对象上的文件夹来源配置：校验 C1–C14 每条一个拒绝用例与通过用例、整份替换、回显。真实网盘实现 + 测试库。
 *
 * <p>夹具：对象「合同」与指向它的「凭证」（单值关联「所属」）；业务空间里的两个普通文件夹。
 */
class RecordFolderConfigIntegrationTest {
    private static DriveFolderTestBed bed;
    private RecordFolderFixture x;
    private DataCenter.Definition contract;
    private DataCenter.Definition voucher;
    private long[] first;
    private long[] second;

    @BeforeAll
    static void open() throws Exception {
        connect();
        bed = RecordFolderFixture.bed();
    }

    @AfterAll
    static void shutdown() {
        if (bed != null) bed.close();
        close();
    }

    @BeforeEach
    void setup() {
        x = new RecordFolderFixture(bed);
        contract = x.contract();
        voucher = x.voucher(contract);
        first = x.bizFolder("合同资料");
        second = x.bizFolder("公司制度");
    }

    @AfterEach
    void cleanup() {
        x.cleanup();
    }

    private void refused(
            String message, DataCenter.Definition d, RecordFolders.SourceInput... inputs) {
        refused(message, d.objectId(), OWNER, inputs);
    }

    private void refused(
            String message, String objectId, long actor, RecordFolders.SourceInput... inputs) {
        int before = stored();
        rejected(
                message,
                () ->
                        x.configs.save(
                                new RecordFolders.SaveConfig(objectId, Arrays.asList(inputs)),
                                actor));
        assertThat(stored()).as("整份拒绝，不部分保存").isEqualTo(before);
    }

    /** 两个对象未删除的来源行数。 */
    private int stored() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_record_folder_source WHERE object_id IN (?,?)"
                        + " AND deleted=0",
                Integer.class,
                contract.objectId(),
                voucher.objectId());
    }

    private static RecordFolders.SourceInput sub(long[] folder, String label) {
        return folderSource(folder, "RECORD_SUBFOLDER", label, null);
    }

    private static RecordFolders.SourceInput direct(long[] folder, String label) {
        return folderSource(folder, "DIRECT", label, null);
    }

    private static RecordFolders.SourceInput keep(RecordFolders.Source saved, String label) {
        return new RecordFolders.SourceInput(
                saved.id(),
                saved.kind(),
                saved.placement(),
                label,
                saved.spaceId(),
                saved.entryId(),
                saved.relationFieldId(),
                saved.targetSourceId(),
                saved.createMode(),
                saved.nameTemplate());
    }

    private static RecordFolders.SourceInput named(
            long[] folder, String createMode, String separator, RecordFolders.NamePart... parts) {
        return new RecordFolders.SourceInput(
                null,
                "FOLDER",
                "RECORD_SUBFOLDER",
                "合同文件",
                folder[0],
                folder[1],
                null,
                null,
                createMode,
                new RecordFolders.NameTemplate(separator, List.of(parts)));
    }

    private RecordFolders.NamePart fieldPart(String code) {
        return new RecordFolders.NamePart("FIELD", x.live.field(contract, code), null);
    }

    private static RecordFolders.NamePart textPart(String text) {
        return new RecordFolders.NamePart("TEXT", null, text);
    }

    @Test
    void acceptsBothKindsAndEchoesThemBack() {
        RecordFolders.Source a1 = x.configure(contract, sub(first, "")).getFirst();
        List<RecordFolders.Source> saved =
                x.configure(
                        voucher,
                        relationSource(x.relationField(voucher), a1.id(), "DIRECT", "", null),
                        relationSource(
                                x.relationField(voucher),
                                a1.id(),
                                "RECORD_SUBFOLDER",
                                "本凭证文件",
                                "ON_SAVE"),
                        direct(second, " 制度 "));

        assertThat(a1.kind()).isEqualTo("FOLDER");
        assertThat(a1.placement()).isEqualTo("RECORD_SUBFOLDER");
        assertThat(a1.objectId()).isEqualTo(contract.objectId());
        assertThat(a1.label()).isEmpty();
        assertThat(a1.displayLabel()).as("没配页签名称用文件夹当前的名字").isEqualTo("合同资料");
        assertThat(a1.folderPath())
                .isEqualTo(bed.folders.describe(first[1]).spaceName() + " / 合同资料");
        assertThat(a1.createMode()).isEqualTo("ON_FIRST_WRITE");
        assertThat(a1.nameTemplate()).isNull();
        assertThat(a1.problem()).isNull();
        assertThat(a1.relationFieldId()).isNull();
        assertThat(a1.targetSourceId()).isNull();

        assertThat(saved)
                .extracting(RecordFolders.Source::kind)
                .containsExactly("RELATION", "RELATION", "FOLDER");
        RecordFolders.Source b1 = saved.get(0);
        assertThat(b1.displayLabel()).as("没配页签名称用关联字段的名字").isEqualTo("所属");
        assertThat(b1.relationName()).isEqualTo("所属");
        assertThat(b1.relationFieldId()).isEqualTo(x.relationField(voucher));
        assertThat(b1.targetSourceId()).isEqualTo(a1.id());
        assertThat(b1.targetObjectId()).isEqualTo(contract.objectId());
        assertThat(b1.targetObjectName()).isEqualTo("合同");
        assertThat(b1.targetLabel()).isEqualTo("合同资料");
        assertThat(b1.spaceId()).isNull();
        assertThat(b1.entryId()).isNull();
        assertThat(b1.folderPath()).isNull();
        assertThat(b1.problem()).isNull();
        assertThat(saved.get(1).createMode()).isEqualTo("ON_SAVE");
        assertThat(saved.get(2).label()).as("页签名称去首尾空白").isEqualTo("制度");

        // 别人读：他在网盘里看不到那个文件夹时不给路径
        assertThat(x.configs.list(contract.objectId(), JIA).getFirst().folderPath())
                .isEqualTo("（你在网盘里没有查看这个文件夹的权限）");
        // 没有来源的对象返回空清单
        assertThat(x.configs.list(x.contract().objectId(), OWNER)).isEmpty();
        // 团队空间里的文件夹同样可以关联
        long team = bed.space("T-config", "TEAM", OWNER);
        long shared = bed.folder(team, 0L, "部门共享", OWNER);
        assertThat(
                        x.configure(
                                        contract,
                                        keep(a1, ""),
                                        direct(new long[] {team, shared}, "部门共享"))
                                .get(1)
                                .problem())
                .isNull();
    }

    @Test
    void listReportsBrokenConfigurations() {
        RecordFolders.Source a1 = x.configure(contract, sub(first, "合同文件")).getFirst();
        x.configure(
                voucher,
                relationSource(x.relationField(voucher), a1.id(), "DIRECT", "合同文件夹", null));

        bed.entryService.trashEntryList(List.of(first[1]), OWNER);
        assertThat(x.configs.list(contract.objectId(), OWNER).getFirst().problem())
                .isEqualTo("文件夹在回收站里，请联系网盘管理员恢复");
        bed.entryService.purgeEntryList(List.of(first[1]), OWNER);
        RecordFolders.Source gone = x.configs.list(contract.objectId(), OWNER).getFirst();
        assertThat(gone.problem()).isEqualTo("文件夹已被删除");
        assertThat(gone.folderPath()).isNull();
        assertThat(gone.displayLabel()).isEqualTo("合同文件");

        jdbc.update(
                "UPDATE public.nocode_record_folder_source SET deleted=1 WHERE id=?",
                Long.valueOf(a1.id()));
        RecordFolders.Source dangling = x.configs.list(voucher.objectId(), OWNER).getFirst();
        assertThat(dangling.problem()).isEqualTo("配置已失效");
        assertThat(dangling.targetLabel()).isNull();
    }

    @Test
    void c1ObjectMustBePublished() {
        ObjectDraft draft = x.live.fixture.create("draftonly");
        for (String objectId : Arrays.asList(draft.id(), "999999999", "abc", "", null))
            rejected(
                    "数据对象不存在或尚未发布",
                    () ->
                            x.configs.save(
                                    new RecordFolders.SaveConfig(
                                            objectId, List.of(sub(first, "合同文件"))),
                                    OWNER));
        rejected("数据对象不存在或尚未发布", () -> x.configs.candidates(draft.id(), OWNER));
        rejected("数据对象不存在或尚未发布", () -> x.configs.nameFields("999999999", OWNER));
    }

    @Test
    void c2AtMostSixSources() {
        List<RecordFolders.SourceInput> seven = new ArrayList<>();
        for (int index = 0; index < 7; index++)
            seven.add(sub(x.bizFolder("夹" + index), "页签" + index));

        refused("一个对象最多配置 6 个文件夹", contract, seven.toArray(RecordFolders.SourceInput[]::new));
        assertThat(
                        x.configure(
                                contract,
                                seven.subList(0, 6).toArray(RecordFolders.SourceInput[]::new)))
                .hasSize(6);
    }

    @Test
    void c3KindPlacementAndIdsMustBeValid() {
        RecordFolders.Source own = x.configure(contract, sub(first, "合同文件")).getFirst();
        RecordFolders.Source other = x.configure(voucher, direct(second, "制度")).getFirst();

        refused(
                "文件夹配置无效",
                contract,
                new RecordFolders.SourceInput(
                        null, "LINK", "DIRECT", "x", first[0], first[1], null, null, null, null));
        refused(
                "文件夹配置无效",
                contract,
                new RecordFolders.SourceInput(
                        null, "FOLDER", "INSIDE", "x", first[0], first[1], null, null, null, null));
        refused("文件夹配置无效", contract, (RecordFolders.SourceInput) null);
        // 别的对象的来源编号、不存在的编号、同一个编号出现两次
        refused("文件夹配置无效", contract, keep(other, "制度"));
        refused(
                "文件夹配置无效",
                contract,
                new RecordFolders.SourceInput(
                        "999999999",
                        "FOLDER",
                        "DIRECT",
                        "x",
                        first[0],
                        first[1],
                        null,
                        null,
                        null,
                        null));
        refused("文件夹配置无效", contract, keep(own, "甲"), keep(own, "乙"));
        // 已删除的来源编号
        x.configure(contract);
        refused("文件夹配置无效", contract, keep(own, "合同文件"));
        // 指定文件夹缺空间或节点
        refused(
                "文件夹配置无效",
                contract,
                new RecordFolders.SourceInput(
                        null, "FOLDER", "DIRECT", "x", null, first[1], null, null, null, null));
    }

    @Test
    void c4LabelsAreBoundedAndUnique() {
        refused("页签名称不能超过 20 个字", contract, sub(first, "页".repeat(21)));
        refused("页签名称「合同文件」重复", contract, sub(first, "合同文件"), direct(second, " 合同文件 "));
        assertThat(
                        x.configure(
                                contract,
                                sub(first, "页".repeat(20)),
                                direct(second, ""),
                                direct(first, "")))
                .as("20 个字可以；空的页签名称不算重复")
                .hasSize(3);
    }

    @Test
    void c5FolderMustBeAUsableOrdinaryFolderOutsidePersonalSpace() {
        long file = bed.file(first[0], first[1], "a.txt", OWNER);
        long managed = bed.bizFiles.ensureDirectory(first[0], List.of("附件归档"), OWNER);
        long trashed = bed.folder(first[0], 0L, "已删", OWNER);
        bed.entryService.trashEntryList(List.of(trashed), OWNER);
        long disabledSpace = bed.space("B-disabled", "BIZ", null);
        long inDisabled = bed.folder(disabledSpace, 0L, "停用空间里的", OWNER);
        jdbc.update("UPDATE public.drive_space SET status=1 WHERE id=?", disabledSpace);

        for (long[] folder :
                List.of(
                        new long[] {first[0], -1L},
                        new long[] {first[0], file},
                        new long[] {first[0], managed},
                        new long[] {first[0], trashed},
                        new long[] {disabledSpace, inDisabled},
                        new long[] {second[0], first[1]}))
            refused("所选文件夹不存在或已不可用", contract, sub(folder, "合同文件"));

        long personal = bed.space("P-own", "PERSONAL", OWNER);
        long mine = bed.folder(personal, 0L, "我的", OWNER);
        refused("不能关联个人空间里的文件夹", contract, sub(new long[] {personal, mine}, "合同文件"));
    }

    @Test
    void c6OnlyDriveManagersAppointAFolderButUnchangedOnesAreNotRechecked() {
        refused("你在网盘里没有管理这个文件夹的权限", contract.objectId(), JIA, sub(first, "合同文件"));
        // 可编辑不够，要可管理
        long team = bed.space("T-c6", "TEAM", OWNER);
        long shared = bed.folder(team, 0L, "部门共享", OWNER);
        bed.grant(team, shared, JIA, "EDITOR");
        refused(
                "你在网盘里没有管理这个文件夹的权限",
                contract.objectId(),
                JIA,
                direct(new long[] {team, shared}, "部门共享"));

        RecordFolders.Source saved = x.configure(contract, sub(first, "合同文件")).getFirst();
        // 别人配的、指向没变：我没有那个文件夹的权限也能保存（改页签名称、放法、加别的行）
        List<RecordFolders.Source> byOther =
                x.configs.save(
                        new RecordFolders.SaveConfig(
                                contract.objectId(), List.of(keep(saved, "合同资料夹"))),
                        JIA);
        assertThat(byOther.getFirst().label()).isEqualTo("合同资料夹");
        // 指向一变就要重新校验
        refused(
                "你在网盘里没有管理这个文件夹的权限",
                contract.objectId(),
                JIA,
                new RecordFolders.SourceInput(
                        saved.id(),
                        "FOLDER",
                        "RECORD_SUBFOLDER",
                        "合同文件",
                        second[0],
                        second[1],
                        null,
                        null,
                        null,
                        null));
    }

    @Test
    void c7SameFolderAndPlacementOnlyOnce() {
        refused("同一个文件夹同一种放法不能添加两次", contract, sub(first, "甲"), sub(first, "乙"));
        assertThat(x.configure(contract, sub(first, "甲"), direct(first, "乙")))
                .as("同一个文件夹的两种放法可以并存")
                .hasSize(2);

        RecordFolders.Source a1 = x.configs.list(contract.objectId(), OWNER).getFirst();
        String field = x.relationField(voucher);
        refused(
                "同一个文件夹同一种放法不能添加两次",
                voucher,
                relationSource(field, a1.id(), "DIRECT", "甲", null),
                relationSource(field, a1.id(), "DIRECT", "乙", null));
        assertThat(
                        x.configure(
                                voucher,
                                relationSource(field, a1.id(), "DIRECT", "甲", null),
                                relationSource(field, a1.id(), "RECORD_SUBFOLDER", "乙", null)))
                .hasSize(2);
    }

    @Test
    void c8RelationFieldMustBeASingleRelationOnTheMainTable() {
        RecordFolders.Source a1 = x.configure(contract, sub(first, "合同文件")).getFirst();
        DataCenter.Definition many = x.live.related(contract, "MANY_TO_MANY", "RESTRICT");
        DataCenter.Relation relation = many.relations().getFirst();

        refused(
                "关联字段不存在，或不是主表上的单值关联",
                voucher,
                relationSource(x.live.field(voucher, "name"), a1.id(), "DIRECT", "x", null));
        refused(
                "关联字段不存在，或不是主表上的单值关联",
                voucher,
                relationSource("999999", a1.id(), "DIRECT", "x", null));
        refused("关联字段不存在，或不是主表上的单值关联", voucher, relationSource(null, a1.id(), "DIRECT", "x", null));
        rejected(
                "关联字段不存在，或不是主表上的单值关联",
                () ->
                        x.configure(
                                many,
                                relationSource(
                                        BusinessFields.key(relation),
                                        a1.id(),
                                        "DIRECT",
                                        "x",
                                        null)));

        // 候选：单值关联列出对方的来源；多对多列出但不可选；对方没有来源时说明原因
        List<RecordFolders.Candidate> candidates = x.configs.candidates(voucher.objectId(), OWNER);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().relationFieldId()).isEqualTo(x.relationField(voucher));
        assertThat(candidates.getFirst().relationName()).isEqualTo("所属");
        assertThat(candidates.getFirst().targetObjectId()).isEqualTo(contract.objectId());
        assertThat(candidates.getFirst().targetObjectName()).isEqualTo("合同");
        assertThat(candidates.getFirst().sources())
                .containsExactly(new RecordFolders.CandidateSource(a1.id(), "合同文件"));
        assertThat(candidates.getFirst().disabledReason()).isNull();
        assertThat(x.configs.candidates(many.objectId(), OWNER).getFirst().disabledReason())
                .isEqualTo("多对多关联暂不支持");
        assertThat(x.configs.candidates(many.objectId(), OWNER).getFirst().sources()).isEmpty();
        DataCenter.Definition lonely = x.related(voucher, "回单");
        RecordFolders.Candidate empty = x.configs.candidates(lonely.objectId(), OWNER).getFirst();
        assertThat(empty.sources()).isEmpty();
        assertThat(empty.disabledReason()).isEqualTo("对方还没有配置文件夹");
        assertThat(x.configs.candidates(contract.objectId(), OWNER)).isEmpty();
    }

    @Test
    void c9TargetSourceMustBelongToTheRelatedObject() {
        RecordFolders.Source a1 = x.configure(contract, sub(first, "合同文件")).getFirst();
        RecordFolders.Source own = x.configure(voucher, direct(second, "制度")).getFirst();
        String field = x.relationField(voucher);

        refused("对方对象没有这个文件夹", voucher, relationSource(field, "999999999", "DIRECT", "x", null));
        refused("对方对象没有这个文件夹", voucher, relationSource(field, null, "DIRECT", "x", null));
        // 凭证自己的来源不是「合同」的来源
        refused(
                "对方对象没有这个文件夹",
                voucher,
                keep(own, "制度"),
                relationSource(field, own.id(), "DIRECT", "x", null));
        // 对方的来源已删除
        x.configure(contract);
        refused("对方对象没有这个文件夹", voucher, relationSource(field, a1.id(), "DIRECT", "x", null));
    }

    @Test
    void c10ChainsAreAtMostThreeRelationsDeepAndAcyclic() {
        RecordFolders.Source a1 = x.configure(contract, sub(first, "合同文件")).getFirst();
        RecordFolders.Source b1 =
                x.configure(
                                voucher,
                                relationSource(
                                        x.relationField(voucher), a1.id(), "DIRECT", "一层", null))
                        .getFirst();
        DataCenter.Definition third = x.related(voucher, "三层");
        DataCenter.Definition fourth = x.related(third, "四层");
        DataCenter.Definition fifth = x.related(fourth, "五层");
        RecordFolders.Source c1 =
                x.configure(
                                third,
                                relationSource(
                                        x.relationField(third), b1.id(), "DIRECT", "二层", null))
                        .getFirst();
        RecordFolders.Source d1 =
                x.configure(
                                fourth,
                                relationSource(
                                        x.relationField(fourth), c1.id(), "DIRECT", "三层", null))
                        .getFirst();
        assertThat(d1.problem()).as("隔 3 层可以").isNull();
        rejected(
                "文件夹关联最多隔 3 层",
                () ->
                        x.configure(
                                fifth,
                                relationSource(
                                        x.relationField(fifth), d1.id(), "DIRECT", "四层", null)));
        assertThat(x.configs.list(fifth.objectId(), OWNER)).isEmpty();

        // 成环：给「合同」加一个指回「凭证」的关联，再把合同的来源改成用凭证的那个来源
        DataCenter.Definition looped = withBackReference(contract, voucher);
        String back =
                looped.relations().stream()
                        .filter(relation -> relation.code().equals("back"))
                        .findFirst()
                        .orElseThrow()
                        .fieldId();
        refused(
                "文件夹关联形成了循环",
                contract,
                new RecordFolders.SourceInput(
                        a1.id(),
                        "RELATION",
                        "DIRECT",
                        "合同文件",
                        null,
                        null,
                        back,
                        b1.id(),
                        null,
                        null));
        assertThat(x.configs.list(contract.objectId(), OWNER).getFirst().kind())
                .isEqualTo("FOLDER");
    }

    /** 给对象加一个指向 target 的单值关联「回指」并重新发布。 */
    private DataCenter.Definition withBackReference(
            DataCenter.Definition object, DataCenter.Definition target) {
        DataCenter.Design edited =
                designs.editPublished(
                        new DataCenter.Revision(
                                object.objectId(),
                                designs.get(object.objectId()).draft().lockVersion(),
                                "加回指关联"),
                        OWNER);
        List<DataCenter.Relation> relations = new ArrayList<>(edited.relations());
        relations.add(
                new DataCenter.Relation(
                        null,
                        "back",
                        "回指",
                        "REFERENCE",
                        target.objectId(),
                        null,
                        null,
                        false,
                        "RESTRICT"));
        return x.live.publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                x.live.fixture.edit(
                                        edited.draft(),
                                        edited.draft().fields(),
                                        List.of(),
                                        edited.draft().titleFieldId()),
                                edited.settings(),
                                edited.fieldOptions(),
                                relations,
                                edited.indexes(),
                                edited.details(),
                                edited.mainBinding()),
                        OWNER));
    }

    @Test
    void c11ReferencedSourceCannotBeDeleted() {
        RecordFolders.Source a1 = x.configure(contract, sub(first, "合同文件")).getFirst();
        x.configure(
                voucher,
                relationSource(x.relationField(voucher), a1.id(), "DIRECT", "合同文件夹", null));

        refused("「凭证」的「合同文件夹」还在用这个文件夹，不能删除", contract);
        refused("「凭证」的「合同文件夹」还在用这个文件夹，不能删除", contract, direct(second, "制度"));
        // 引用它的来源删掉之后就能删
        x.configure(voucher);
        assertThat(x.configure(contract)).isEmpty();
        assertThat(stored()).isZero();
    }

    @Test
    void c12CreateModeIsValidatedOnlyForSubfolders() {
        refused("文件夹配置无效", contract, folderSource(first, "RECORD_SUBFOLDER", "合同文件", "LATER"));

        List<RecordFolders.Source> saved =
                x.configure(
                        contract,
                        folderSource(first, "RECORD_SUBFOLDER", "默认", null),
                        folderSource(second, "RECORD_SUBFOLDER", "保存就建", "ON_SAVE"),
                        // 共用一个文件夹时不校验、一律归一
                        folderSource(second, "DIRECT", "共用", "LATER"),
                        new RecordFolders.SourceInput(
                                null,
                                "FOLDER",
                                "DIRECT",
                                "共用二",
                                first[0],
                                first[1],
                                null,
                                null,
                                "ON_SAVE",
                                new RecordFolders.NameTemplate("?", List.of())));

        assertThat(saved)
                .extracting(RecordFolders.Source::createMode)
                .containsExactly("ON_FIRST_WRITE", "ON_SAVE", "ON_FIRST_WRITE", "ON_FIRST_WRITE");
        assertThat(saved.get(3).nameTemplate()).isNull();
        assertThat(
                        jdbc.queryForList(
                                "SELECT create_mode || '|' || name_template FROM"
                                        + " public.nocode_record_folder_source WHERE object_id=?"
                                        + " AND deleted=0 ORDER BY sort_no",
                                String.class,
                                contract.objectId()))
                .containsExactly(
                        "ON_FIRST_WRITE|", "ON_SAVE|", "ON_FIRST_WRITE|", "ON_FIRST_WRITE|");
    }

    @Test
    void c13NameTemplateShapeIsValidated() {
        RecordFolders.NamePart code = fieldPart("code");
        refused(
                "文件夹名称最多由 5 段组成",
                contract,
                named(
                        first,
                        null,
                        "-",
                        code,
                        textPart("一"),
                        textPart("二"),
                        textPart("三"),
                        textPart("四"),
                        textPart("五")));
        refused("文件夹名称里至少要有一个字段", contract, named(first, null, "-", textPart("合同")));
        refused("文件夹名称里至少要有一个字段", contract, named(first, null, "-"));
        refused("文件夹名称的分隔符不支持", contract, named(first, null, "/", code));
        refused("文件夹名称的分隔符不支持", contract, named(first, null, "--", code));
        for (String text : new String[] {"", "   ", "字".repeat(21), "a/b", "a\\b", "a\nb", "a\tb"})
            refused(
                    "固定文字要在 1 到 20 个字之间，且不能包含 / 或 \\",
                    contract,
                    named(first, null, "-", code, textPart(text)));
        refused(
                "文件夹配置无效",
                contract,
                named(first, null, "-", code, new RecordFolders.NamePart("OTHER", null, "x")));

        for (String separator : new String[] {"-", "_", " ", " · ", "", null}) {
            RecordFolders.Source saved =
                    x.configure(
                                    contract,
                                    named(
                                            first,
                                            "ON_SAVE",
                                            separator,
                                            code,
                                            fieldPart("name"),
                                            textPart(" 资料 ")))
                            .getFirst();
            assertThat(saved.nameTemplate().separator())
                    .isEqualTo(separator == null ? "" : separator);
            assertThat(saved.nameTemplate().parts())
                    .containsExactly(
                            code,
                            fieldPart("name"),
                            new RecordFolders.NamePart("TEXT", null, "资料"));
            x.configure(contract);
        }
    }

    @Test
    void c14NameFieldsMustBeNameableAndDistinct() {
        DataCenter.Definition typed =
                x.live.publish(
                        designs.save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                null,
                                                null,
                                                x.live.fixture.prefix + "typed",
                                                "各种类型",
                                                null,
                                                "biz_" + x.live.fixture.prefix + "typed",
                                                "name",
                                                List.of(
                                                        typedField("name", "名称", "TEXT"),
                                                        typedField("memo", "备注", "TEXTAREA"),
                                                        typedField("amount", "金额", "DECIMAL"),
                                                        typedField("count", "数量", "INTEGER"),
                                                        typedField("flag", "是否", "BOOLEAN"),
                                                        typedField("due", "到期", "DATETIME")),
                                                List.of()),
                                        DataCenter.Settings.defaults(),
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                OWNER));
        for (String code : List.of("memo", "amount", "flag")) {
            String name =
                    typed.fields().stream()
                            .filter(field -> field.code().equals(code))
                            .findFirst()
                            .orElseThrow()
                            .name();
            rejected(
                    "字段「" + name + "」不能用来给文件夹命名",
                    () ->
                            x.configure(
                                    typed,
                                    named(
                                            first,
                                            null,
                                            "-",
                                            new RecordFolders.NamePart(
                                                    "FIELD", x.live.field(typed, code), null))));
        }
        refused(
                "字段「999999」不能用来给文件夹命名",
                contract,
                named(first, null, "-", new RecordFolders.NamePart("FIELD", "999999", null)));
        // 别的对象的字段
        refused(
                "字段「" + x.live.field(typed, "count") + "」不能用来给文件夹命名",
                contract,
                named(
                        first,
                        null,
                        "-",
                        new RecordFolders.NamePart("FIELD", x.live.field(typed, "count"), null)));
        refused(
                "文件夹名称里的字段不能重复",
                contract,
                named(first, null, "-", fieldPart("code"), fieldPart("code")));

        assertThat(x.configs.nameFields(typed.objectId(), OWNER))
                .extracting(RecordFolders.NameField::name, RecordFolders.NameField::type)
                .containsExactly(
                        tuple("名称", "TEXT"), tuple("数量", "INTEGER"), tuple("到期", "DATETIME"));
        assertThat(x.configs.nameFields(contract.objectId(), OWNER))
                .extracting(RecordFolders.NameField::name, RecordFolders.NameField::type)
                .containsExactly(
                        tuple("编号", "TEXT"),
                        tuple("名称", "TEXT"),
                        tuple("签订日期", "DATE"),
                        tuple("类型", "SELECT"));
        // 单值关联的关联列按「引用」给出，不管它在定义里是整数还是文本
        assertThat(x.configs.nameFields(voucher.objectId(), OWNER))
                .extracting(RecordFolders.NameField::name, RecordFolders.NameField::type)
                .containsExactly(tuple("名称", "TEXT"), tuple("所属", "REFERENCE"));
        assertThat(
                        x.configure(
                                        voucher,
                                        new RecordFolders.SourceInput(
                                                null,
                                                "FOLDER",
                                                "RECORD_SUBFOLDER",
                                                "凭证文件",
                                                first[0],
                                                first[1],
                                                null,
                                                null,
                                                null,
                                                new RecordFolders.NameTemplate(
                                                        " · ",
                                                        List.of(
                                                                new RecordFolders.NamePart(
                                                                        "FIELD",
                                                                        x.relationField(voucher),
                                                                        null)))))
                                .getFirst()
                                .problem())
                .isNull();
    }

    private static FieldDefinition typedField(String code, String name, String type) {
        return new FieldDefinition(
                code,
                null,
                code,
                name,
                type,
                "TEXT".equals(type) ? 100 : null,
                "DECIMAL".equals(type) ? 18 : null,
                "DECIMAL".equals(type) ? 2 : null,
                false,
                false,
                0);
    }

    @Test
    void saveReplacesTheWholeList() {
        long[] third = x.bizFolder("图纸");
        List<RecordFolders.Source> before =
                x.configure(contract, sub(first, "一"), direct(second, "二"), sub(third, "三"));

        List<RecordFolders.Source> after =
                x.configure(
                        contract,
                        keep(before.get(2), "三改"),
                        direct(first, "新增"),
                        keep(before.get(0), "一"));

        assertThat(after).extracting(RecordFolders.Source::label).containsExactly("三改", "新增", "一");
        assertThat(after.get(0).id()).isEqualTo(before.get(2).id());
        assertThat(after.get(2).id()).isEqualTo(before.get(0).id());
        assertThat(after.get(1).id())
                .isNotIn(before.stream().map(RecordFolders.Source::id).toList());
        assertThat(
                        jdbc.queryForList(
                                "SELECT label || ':' || sort_no || ':' || deleted FROM"
                                        + " public.nocode_record_folder_source WHERE object_id=?"
                                        + " ORDER BY id",
                                String.class,
                                contract.objectId()))
                .containsExactly("一:2:0", "二:1:1", "三改:0:0", "新增:1:0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT updater FROM public.nocode_record_folder_source WHERE id=?",
                                String.class,
                                Long.valueOf(before.get(1).id())))
                .isEqualTo(Long.toString(OWNER));
        // 把种类从「指定文件夹」改成「用关联记录的文件夹」再改回来：不适用的列置空
        RecordFolders.Source a1 = after.get(2);
        RecordFolders.Source switched = x.configure(voucher, direct(second, "先指定")).getFirst();
        RecordFolders.Source relation =
                x.configure(
                                voucher,
                                new RecordFolders.SourceInput(
                                        switched.id(),
                                        "RELATION",
                                        "DIRECT",
                                        "改成关联",
                                        null,
                                        null,
                                        x.relationField(voucher),
                                        a1.id(),
                                        null,
                                        null))
                        .getFirst();
        assertThat(relation.id()).isEqualTo(switched.id());
        assertThat(relation.spaceId()).isNull();
        assertThat(relation.entryId()).isNull();
        assertThat(relation.targetSourceId()).isEqualTo(a1.id());
    }
}
