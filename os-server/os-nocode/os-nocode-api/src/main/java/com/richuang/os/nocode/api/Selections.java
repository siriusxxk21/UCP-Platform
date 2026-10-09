package com.richuang.os.nocode.api;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 授权清单里的「全部」：被存储的哨兵 {@code "*"}。
 *
 * <p>「全部」= 该清单恰好一个元素 {@code "*"}，含义是「上一层允许的全部」，随上一层自动同步。字段、明细、关系的稳定 ID 都是整数，不会与哨兵相撞。
 * 存的是什么样就传什么样，只在算出某个人的有效授权的最后一步按对象版本展开一次；这里只有不依赖数据库的集合语义。
 */
public final class Selections {
    public static final String ALL = "*";

    private Selections() {}

    /** 恰好一个元素且为 "*"。null、空集合都不是全部。 */
    public static boolean isAll(Collection<String> stored) {
        return stored != null && stored.size() == 1 && stored.contains(ALL);
    }

    /** "*" 与具体项混用时拒绝；其余形态（含 null、空）不在这里判断。 */
    public static void requireWellFormed(Collection<String> stored, String label) {
        if (stored != null && stored.size() > 1 && stored.contains(ALL))
            throw invalid("「全部」不能与具体项同时选择：" + label);
    }

    /** 全部 ⇒ universe 原顺序；清单 ⇒ stored 里仍在 universe 的项（保持 stored 的迭代顺序）。 */
    public static List<String> resolve(Collection<String> stored, List<String> universe) {
        if (universe == null) return List.of();
        if (isAll(stored)) return List.copyOf(universe);
        if (stored == null) return List.of();
        Set<String> available = new HashSet<>(universe);
        List<String> result = new ArrayList<>();
        for (String id : stored) if (available.contains(id)) result.add(id);
        return List.copyOf(result);
    }

    /** 两层取交集：全部 ∩ X = X；X ∩ 全部 = X；全部 ∩ 全部 = 全部；否则普通交集。永远不会把清单放大成全部。 */
    public static Set<String> intersect(Set<String> a, Set<String> b) {
        if (a == null || b == null) return Set.of();
        if (isAll(a)) return Set.copyOf(b);
        if (isAll(b)) return Set.copyOf(a);
        Set<String> result = new HashSet<>(a);
        result.retainAll(b);
        return Set.copyOf(result);
    }

    /**
     * 保存前规范化：全部 ⇒ 原样；清单 ⇒ 去掉不在 universe 的 ID（已停用或不存在的残留）。
     *
     * <p>不把「恰好等于全部」的清单变成全部：人明确点出来的清单保持清单，以后新增的项不会自动进来。
     */
    public static Set<String> clean(Set<String> stored, Collection<String> universe) {
        if (stored == null) return Set.of();
        if (isAll(stored)) return Set.of(ALL);
        Set<String> available = universe == null ? Set.of() : new HashSet<>(universe);
        Set<String> result = new LinkedHashSet<>();
        for (String id : stored) if (available.contains(id)) result.add(id);
        return Collections.unmodifiableSet(result);
    }
}
