package com.example.pep.sdk.core.request;

import com.example.pep.sdk.core.exception.PapSdkException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Substitutes {name} placeholders in a URL template from a name->value map. */
public final class EndpointResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)\\}");

    public String resolve(String template, Map<String, String> pathVariables) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = pathVariables.get(key);
            if (value == null) {
                throw new PapSdkException(
                        "No value for path variable {" + key + "} in template " + template);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(
                    URLEncoder.encode(value, StandardCharsets.UTF_8)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
