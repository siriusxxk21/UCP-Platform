import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.nocode.tools.NocodeToolContext;
import java.nio.file.Path;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅清理本轮登记且没有产品删除入口的独立夹具，沿用开发工程的 Spring 数据源。 */
public class FixtureCleanup {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path ledger = Path.of(args[0]);
        JsonNode data = mapper.readTree(ledger.toFile());
        String prefix = data.path("prefix").asText();
        if (!prefix.matches("mob[a-z0-9]+")) throw new IllegalArgumentException("Invalid fixture prefix");
        try (ConfigurableApplicationContext context = NocodeToolContext.open()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                for (JsonNode item : data.path("owned")) {
                    if (!item.path("requiresDatabaseCleanup").asBoolean() || item.path("cleaned").asBoolean()) continue;
                    String name = item.path("name").asText();
                    if (!name.startsWith(prefix)) throw new IllegalArgumentException("Fixture name mismatch");
                    String id = item.path("id").asText();
                    int removed;
                    switch (item.path("kind").asText()) {
                        case "template" -> removed = jdbc.update("""
                            DELETE FROM public.nocode_task_template t WHERE t.id=? AND t.name=?
                            AND t.published_version IS NULL
                            AND NOT EXISTS (SELECT 1 FROM public.nocode_task_template_version v WHERE v.template_id=t.id)
                            AND NOT EXISTS (SELECT 1 FROM public.nocode_task_instance i WHERE i.template_id=t.id)
                            """, id, name);
                        case "empty-business-space" -> removed = jdbc.update("""
                            DELETE FROM public.drive_space s WHERE s.id=? AND s.name=? AND s.type='BIZ'
                            AND s.owner_id IS NULL AND s.used_bytes=0
                            AND NOT EXISTS (SELECT 1 FROM public.drive_entry e WHERE e.space_id=s.id)
                            """, Long.parseLong(id), name);
                        default -> throw new IllegalArgumentException("Unsupported fixture kind");
                    }
                    if (removed != 1) throw new IllegalStateException("Fixture missing, changed or in use: " + id);
                    ((ObjectNode) item).put("cleaned", true);
                }
            });
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(ledger.toFile(), data);
        System.out.println("Registered mobile fixtures cleaned");
    }
}
