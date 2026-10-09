package com.lingan.ucp.module.msg.core;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class MsgStrTemplateUtil {

    public static String parse(String template, Map<String, Object> variables){
        if (StrUtil.isBlank(template) || variables == null) return template;
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(template);
        while (matcher.find()) {
            String tag = matcher.group(0);
            String script = matcher.group(1);
            String value = null;
            if (variables.containsKey(script)) {
                Object v = variables.get(script);
                value = v != null ? v.toString() : "";
            } else {
                ScriptEngine groovy = new ScriptEngineManager().getEngineByName("groovy");
                Bindings bindings = groovy.createBindings();
                bindings.putAll(variables);
                try {
                    value = groovy.eval(script, bindings).toString();
                } catch (ScriptException e) {
                    log.error(e.getMessage(), e);
                }
            }
            template = template.replace(tag, StrUtil.blankToDefault(value, ""));
        }
        return template;
    }

}
