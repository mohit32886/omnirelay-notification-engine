package com.omnirelay.engine.deliverability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class HtmlSanitizerService {

    private static final Pattern DANGEROUS_VECTOR_PATTERN = Pattern.compile(
            "(?i)(<\\s*script\\b|<\\s*iframe\\b|<\\s*object\\b|<\\s*embed\\b|<\\s*form\\b|javascript:|vbscript:|data:text/html|\\bon\\w+\\s*=)");

    private static final Safelist TRANSACTIONAL_EMAIL_SAFELIST = new Safelist()
            .addTags("a", "b", "blockquote", "br", "caption", "code", "div", "em",
                    "h1", "h2", "h3", "h4", "hr", "i", "img", "li", "ol", "p",
                    "pre", "span", "strong", "table", "tbody", "td", "tfoot",
                    "th", "thead", "tr", "u", "ul")
            .addAttributes("a", "href", "title", "target", "rel")
            .addAttributes("img", "src", "alt", "title", "width", "height")
            .addAttributes("table", "border", "cellpadding", "cellspacing", "width", "align", "style")
            .addAttributes("td", "colspan", "rowspan", "align", "valign", "width", "style")
            .addAttributes("th", "colspan", "rowspan", "align", "valign", "width", "style")
            .addAttributes(":all", "class", "align")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "https")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer");

    private final Cleaner cleaner = new Cleaner(TRANSACTIONAL_EMAIL_SAFELIST);
    private final Counter violationCounter;

    public HtmlSanitizerService(MeterRegistry meterRegistry) {
        this.violationCounter = Counter.builder("omnirelay_html_sanitization_violations_total")
                .description("Number of HTML or template variable payloads containing stripped XSS/injection vectors")
                .register(meterRegistry);
    }

    public SanitizationResult sanitizeHtml(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return new SanitizationResult("", false);
        }
        boolean detectedDangerous = DANGEROUS_VECTOR_PATTERN.matcher(rawHtml).find();
        Document dirty = Jsoup.parseBodyFragment(rawHtml);
        Document clean = cleaner.clean(dirty);
        clean.outputSettings().prettyPrint(false);

        // Scrub any CSS expression() or javascript: inside preserved style attributes
        clean.select("[style]").forEach(el -> {
            String style = el.attr("style");
            String lower = style.toLowerCase(Locale.ROOT);
            if (lower.contains("expression(") || lower.contains("javascript:") || lower.contains("url(")) {
                el.removeAttr("style");
            }
        });

        String sanitized = clean.body().html();
        boolean changed = detectedDangerous || (!sanitized.equals(rawHtml) && containsStrippedMarkup(rawHtml, sanitized));
        if (detectedDangerous) {
            violationCounter.increment();
        }
        return new SanitizationResult(sanitized, detectedDangerous || changed);
    }

    public String sanitizePlainVariable(String value) {
        if (value == null) {
            return "";
        }
        if (DANGEROUS_VECTOR_PATTERN.matcher(value).find()) {
            violationCounter.increment();
        }
        // Escape HTML markup inside template variables so variable injection cannot break out of HTML context
        return Jsoup.clean(value, Safelist.none());
    }

    private boolean containsStrippedMarkup(String raw, String sanitized) {
        String lowerRaw = raw.toLowerCase(Locale.ROOT);
        String lowerSanitized = sanitized.toLowerCase(Locale.ROOT);
        return (lowerRaw.contains("<script") && !lowerSanitized.contains("<script"))
                || (lowerRaw.contains("<iframe") && !lowerSanitized.contains("<iframe"))
                || (lowerRaw.contains("javascript:") && !lowerSanitized.contains("javascript:"));
    }

    public record SanitizationResult(String sanitizedHtml, boolean violationsStripped) {
    }
}
