/**
 * 数据对象字段规则的运行期求值：数据联动、公式默认值与引用筛选。规则只从应用固定的对象版本读取，请求不携带任何配置；取数按当前操作者 READ 范围执行，条件经
 * DynamicConditionDTO 与 RecordConditions 参数化编译。保存时的强制与复核由 FieldRuleEnforcer 经 FieldRuleEvaluator
 * 接入。
 */
package com.richuang.os.nocode.runtime.service.rules;
