package dukes;

import dukes.rest.DuckResource;
import dukes.rest.OwnerResource;
import dukes.rest.VetResource;
import dukes.rest.VisitResource;
import eu.hansolo.mcp.rest.McpResource;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import java.util.Set;

@ApplicationPath("")
public class DuckClinicApplication extends Application {

    /** Returning a non-empty set switches classpath scanning off, so the existing REST resources are listed as well as the MCP endpoint in the library JAR. */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OwnerResource.class, DuckResource.class, VisitResource.class, VetResource.class, McpResource.class);
    }
}
