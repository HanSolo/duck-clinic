package dukes;

import dukes.rest.DuckResource;
import dukes.rest.OwnerResource;
import dukes.rest.VetResource;
import dukes.rest.VisitResource;
import eu.hansolo.mcp.rest.McpResource;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import java.util.Set;

@ApplicationPath("/api")
public class DuckClinicApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OwnerResource.class, DuckResource.class, VisitResource.class, VetResource.class, McpResource.class);
    }
}