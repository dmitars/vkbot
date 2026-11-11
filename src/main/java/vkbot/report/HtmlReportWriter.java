package vkbot.report;

import vkbot.model.Author;
import vkbot.model.Post;
import vkbot.model.Topic;
import vkbot.stats.AuthorStats;
import vkbot.stats.Metric;
import vkbot.stats.Report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Самодостаточный HTML-отчёт (один файл, без внешних ресурсов). */
public final class HtmlReportWriter {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter FILE_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    private final boolean includePosts;
    private final int previewLength;

    public HtmlReportWriter(boolean includePosts, int previewLength) {
        this.includePosts = includePosts;
        this.previewLength = previewLength;
    }

    public Path write(Report r, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("report_" + r.generatedAt.atZone(r.zone).format(FILE_DT) + ".html");
        Files.write(file, render(r).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    public String render(Report r) {
        StringBuilder h = new StringBuilder(64 * 1024);
        String title = "Активность в обсуждениях — " + r.community.getName();
        h.append("<!doctype html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(esc(title)).append("</title><style>").append(CSS).append("</style></head><body>\n");

        // ---------- шапка ----------
        h.append("<header><h1>Активность в обсуждениях</h1><p class=\"sub\"><a href=\"")
                .append(esc(r.community.getUrl())).append("\" target=\"_blank\">").append(esc(r.community.getName()))
                .append("</a> · период: <b>").append(fmt(r, r.from)).append(" — ").append(fmt(r, r.to))
                .append("</b> (").append(esc(r.zone.getId())).append(")</p></header>\n");

        // ---------- карточки ----------
        h.append("<section class=\"cards\">");
        card(h, "Сообщений", num(r.totals.get("posts")));
        if (r.totals.containsKey("chars")) {
            card(h, "Символов", num(r.totals.get("chars")));
        }
        card(h, "Авторов", num(r.authors.size()));
        card(h, "Активных обсуждений", num(activeTopics(r)) + " <small>из " + r.totalTopics + "</small>");
        h.append("</section>\n");

        if (!r.failedTopics.isEmpty()) {
            h.append("<div class=\"warn\"><b>Не удалось прочитать обсуждения:</b> ");
            List<String> names = new ArrayList<>();
            for (Topic t : r.failedTopics) {
                names.add(esc(t.getTitle()));
            }
            h.append(String.join(", ", names)).append(". Подробности — в логе.</div>\n");
        }

        // ---------- сводная таблица по авторам ----------
        h.append("<section><h2>По авторам</h2>");
        if (r.authors.isEmpty()) {
            h.append("<p class=\"empty\">За выбранный период сообщений нет.</p>");
        } else {
            h.append("<div class=\"scroll\"><table class=\"sortable\"><thead><tr><th>#</th><th>Автор</th>");
            for (Metric m : r.metrics) {
                h.append("<th class=\"n\">").append(esc(m.title())).append("</th>");
            }
            h.append("</tr></thead><tbody>");
            int i = 0;
            for (AuthorStats a : r.authors) {
                i++;
                Author au = a.getAuthor();
                h.append("<tr><td class=\"n\">").append(i).append("</td><td data-v=\"").append(esc(au.getName()))
                        .append("\"><a href=\"").append(esc(au.getUrl())).append("\" target=\"_blank\">")
                        .append(esc(au.getName())).append("</a>");
                if (au.isCommunity()) {
                    h.append(" <span class=\"tag\">сообщество</span>");
                }
                h.append("</td>");
                for (Metric m : r.metrics) {
                    cell(h, a.getValues().get(m.key()));
                }
                h.append("</tr>");
            }
            h.append("</tbody><tfoot><tr><td></td><td>Итого</td>");
            for (Metric m : r.metrics) {
                cell(h, r.totals.get(m.key()));
            }
            h.append("</tr></tfoot></table></div>");
        }
        h.append("</section>\n");

        // ---------- по обсуждениям ----------
        Map<Topic, Integer> perTopic = new LinkedHashMap<>();
        for (Post p : r.posts) {
            perTopic.merge(p.getTopic(), 1, Integer::sum);
        }
        if (!perTopic.isEmpty()) {
            h.append("<section><h2>По обсуждениям</h2><div class=\"scroll\"><table class=\"sortable\"><thead><tr>")
                    .append("<th>Обсуждение</th><th class=\"n\">Сообщений за период</th></tr></thead><tbody>");
            List<Map.Entry<Topic, Integer>> entries = new ArrayList<>(perTopic.entrySet());
            entries.sort((x, y) -> y.getValue() - x.getValue());
            for (Map.Entry<Topic, Integer> e : entries) {
                Topic t = e.getKey();
                h.append("<tr><td data-v=\"").append(esc(t.getTitle())).append("\"><a target=\"_blank\" href=\"https://vk.com/topic-")
                        .append(r.community.getId()).append('_').append(t.getId()).append("\">")
                        .append(esc(t.getTitle())).append("</a></td>");
                cell(h, e.getValue());
                h.append("</tr>");
            }
            h.append("</tbody></table></div></section>\n");
        }

        // ---------- подробно по каждому автору ----------
        if (includePosts && !r.authors.isEmpty()) {
            List<Metric> postMetrics = new ArrayList<>();
            for (Metric m : r.metrics) {
                if (!r.posts.isEmpty() && hasPostValues(m, r.posts)) {
                    postMetrics.add(m);
                }
            }
            h.append("<section><h2>Сообщения авторов</h2>");
            for (AuthorStats a : r.authors) {
                h.append("<details><summary><b>").append(esc(a.getAuthor().getName())).append("</b> — ")
                        .append(num(a.getPosts().size())).append(" сообщ.</summary>")
                        .append("<div class=\"scroll\"><table><thead><tr><th>Дата</th><th>Обсуждение</th><th>Текст</th>");
                for (Metric m : postMetrics) {
                    h.append("<th class=\"n\">").append(esc(m.title())).append("</th>");
                }
                h.append("</tr></thead><tbody>");
                for (Post p : a.getPosts()) {
                    h.append("<tr><td class=\"nw\"><a target=\"_blank\" href=\"").append(esc(p.getUrl())).append("\">")
                            .append(fmt(r, p.getDate())).append("</a></td><td>").append(esc(p.getTopic().getTitle()))
                            .append("</td><td class=\"txt\">").append(esc(preview(p.getCountedText()))).append("</td>");
                    for (Metric m : postMetrics) {
                        Object v;
                        try {
                            v = m.postValue(p);
                        } catch (RuntimeException ex) {
                            v = "ошибка";
                        }
                        cell(h, v);
                    }
                    h.append("</tr>");
                }
                h.append("</tbody></table></div></details>");
            }
            h.append("</section>\n");
        }

        h.append("<footer>Сформировано ").append(fmt(r, r.generatedAt))
                .append(" · просмотрено обсуждений: ").append(r.scannedTopics.size())
                .append(" · символы считаются без пробелов, переносов строк, невидимых символов и разметки ответов [id|Имя]</footer>\n");
        h.append("<script>").append(JS).append("</script></body></html>\n");
        return h.toString();
    }

    private static long activeTopics(Report r) {
        return r.posts.stream().map(p -> p.getTopic().getId()).distinct().count();
    }

    private static boolean hasPostValues(Metric m, List<Post> posts) {
        try {
            return m.postValue(posts.get(0)) != null
                    || posts.stream().limit(200).anyMatch(p -> m.postValue(p) != null);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String preview(String text) {
        String t = text.replaceAll("\\s+", " ");
        if (t.codePointCount(0, t.length()) <= previewLength) {
            return t;
        }
        return t.substring(0, t.offsetByCodePoints(0, previewLength)) + "…";
    }

    private static void card(StringBuilder h, String label, String value) {
        h.append("<div class=\"card\"><div class=\"v\">").append(value).append("</div><div class=\"l\">")
                .append(esc(label)).append("</div></div>");
    }

    private static void cell(StringBuilder h, Object v) {
        if (v instanceof Number) {
            h.append("<td class=\"n\" data-v=\"").append(v).append("\">").append(num(v)).append("</td>");
        } else {
            String s = v == null ? "" : String.valueOf(v);
            h.append("<td data-v=\"").append(esc(s)).append("\">").append(esc(s)).append("</td>");
        }
    }

    private static String num(Object v) {
        if (!(v instanceof Number)) {
            return v == null ? "" : esc(String.valueOf(v));
        }
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.forLanguageTag("ru"));
        sym.setGroupingSeparator(' ');
        DecimalFormat df = new DecimalFormat("#,##0.##", sym);
        return df.format(v);
    }

    private static String fmt(Report r, Instant i) {
        return i.atZone(r.zone).format(DT);
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String CSS = String.join("\n",
            ":root{--bg:#f6f7f9;--fg:#1d2129;--muted:#6b7280;--card:#fff;--line:#e3e6ea;--accent:#2a5885;--warn:#fff4e5;--warnline:#f0b35a}",
            "@media (prefers-color-scheme:dark){:root{--bg:#16181c;--fg:#e6e8eb;--muted:#9aa1ab;--card:#1f2228;--line:#30343b;--accent:#71aaeb;--warn:#3a2c17;--warnline:#a8742d}}",
            "*{box-sizing:border-box}body{margin:0;padding:24px 16px 48px;background:var(--bg);color:var(--fg);font:15px/1.45 -apple-system,'Segoe UI',Roboto,Arial,sans-serif;max-width:1200px;margin-inline:auto}",
            "a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}",
            "h1{font-size:24px;margin:0 0 4px}h2{font-size:18px;margin:32px 0 12px}.sub{color:var(--muted);margin:0}",
            ".cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin-top:20px}",
            ".card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}.card .v{font-size:24px;font-weight:600;font-variant-numeric:tabular-nums}.card .l{color:var(--muted);font-size:13px}.card small{font-size:13px;color:var(--muted);font-weight:400}",
            ".scroll{overflow-x:auto;background:var(--card);border:1px solid var(--line);border-radius:10px}",
            "table{border-collapse:collapse;width:100%}th,td{padding:8px 12px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}",
            "th{font-size:13px;color:var(--muted);font-weight:600;white-space:nowrap}table.sortable th{cursor:pointer;user-select:none}table.sortable th:hover{color:var(--fg)}",
            "th.asc::after{content:' ▲'}th.desc::after{content:' ▼'}",
            "td.n,th.n{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}td.nw{white-space:nowrap}td.txt{min-width:260px;color:var(--muted)}",
            "tfoot td{font-weight:600;border-bottom:none}tbody tr:last-child td{border-bottom:none}tfoot tr td{border-top:2px solid var(--line)}",
            "details{background:var(--card);border:1px solid var(--line);border-radius:10px;margin-bottom:8px}details>summary{padding:10px 14px;cursor:pointer}details .scroll{border:none;border-top:1px solid var(--line);border-radius:0 0 10px 10px}",
            ".tag{font-size:11px;border:1px solid var(--line);border-radius:6px;padding:1px 6px;color:var(--muted)}",
            ".warn{margin-top:16px;padding:10px 14px;background:var(--warn);border:1px solid var(--warnline);border-radius:10px}",
            ".empty{color:var(--muted)}footer{margin-top:32px;color:var(--muted);font-size:13px}");

    private static final String JS = String.join("\n",
            "document.querySelectorAll('table.sortable').forEach(function(t){",
            " t.querySelectorAll('thead th').forEach(function(th,idx){",
            "  th.addEventListener('click',function(){",
            "   var body=t.tBodies[0],rows=Array.prototype.slice.call(body.rows);",
            "   var dir=th.classList.contains('desc')?1:-1;",
            "   t.querySelectorAll('th').forEach(function(x){x.classList.remove('asc','desc')});",
            "   th.classList.add(dir===1?'asc':'desc');",
            "   rows.sort(function(a,b){",
            "    var x=a.cells[idx].getAttribute('data-v')||a.cells[idx].textContent,y=b.cells[idx].getAttribute('data-v')||b.cells[idx].textContent;",
            "    var nx=parseFloat(x),ny=parseFloat(y);",
            "    if(!isNaN(nx)&&!isNaN(ny))return (nx-ny)*dir;",
            "    return x.localeCompare(y,'ru')*dir;});",
            "   rows.forEach(function(r){body.appendChild(r)});",
            "  });",
            " });",
            "});");
}
