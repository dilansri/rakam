/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rakam.ui;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.IgnoreApi;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.ui.UIPermissionParameterProvider.Project;
import org.rakam.util.AlreadyExistsException;
import org.rakam.util.JsonHelper;
import org.rakam.util.RakamException;
import org.rakam.util.SuccessMessage;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.util.IntegerMapper;
import org.skife.jdbi.v2.util.LongMapper;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Path;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;

// todo check permissions
@Path("/ui/dashboard")
@IgnoreApi
public class DashboardService
        extends HttpService
{
    private final DBI dbi;

    @Inject
    public DashboardService(@Named("ui.metadata.jdbc") JDBCPoolDataSource dataSource)
    {
        dbi = new DBI(dataSource);
    }

    @JsonRequest
    @ApiOperation(value = "Create dashboard")
    @Path("/create")
    @ProtectEndpoint(writeOperation = true)
    public Dashboard create(
            @Named("user_id") Project project,
            @ApiParam("name") String name,
            @ApiParam(value = "options", required = false) Map<String, Object> options)
    {
        try (Handle handle = dbi.open()) {
            int id;
            try {
                id = handle.createQuery("INSERT INTO dashboard (project_id, name, options) VALUES (:project, :name, :options) RETURNING id")
                        .bind("project", project.project)
                        .bind("options", JsonHelper.encode(options))
                        .bind("name", name).map(IntegerMapper.FIRST).first();
            }
            catch (Exception e) {
                if (handle.createQuery("SELECT 1 FROM dashboard WHERE (project_id, name) = (:project, :name)")
                        .bind("project", project.project)
                        .bind("name", name).first() != null) {
                    throw new AlreadyExistsException("Dashboard", BAD_REQUEST);
                }

                throw e;
            }
            return new Dashboard(id, name, null, options);
        }
    }

    @JsonRequest
    @ApiOperation(value = "Get Report")
    @Path("/get")
    public List<DashboardItem> get(@Named("user_id") Project project, @ApiParam("id") int id)
    {
        try (Handle handle = dbi.open()) {
            return handle.createQuery("SELECT id, name, directive, options, refresh_interval, last_updated," +
                    "(case when refresh_interval is null or now() - last_updated > refresh_interval * INTERVAL '1 second' then null else data end)" +
                    " FROM dashboard_items WHERE dashboard = (SELECT id FROM dashboard WHERE project_id = :project AND id = :id)")
                    .bind("project", project.project)
                    .bind("id", id)
                    .map((i, r, statementContext) -> {
                        return new DashboardItem(r.getInt(1),
                                r.getString(2), r.getString(3),
                                JsonHelper.read(r.getString(4), Map.class),
                                r.getObject(5) == null ? null : Duration.ofSeconds(r.getInt(5)),
                                r.getTimestamp(6) != null ? r.getTimestamp(6).toInstant() : null, r.getBytes(7));
                    }).list();
        }
    }

    @JsonRequest
    @ApiOperation(value = "Cache report data")
    @Path("/cache-item-data")
    public SuccessMessage cache(
            @Named("user_id") Project project,
            @ApiParam("item_id") int item_id,
            @ApiParam("data") byte[] data)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("UPDATE dashboard_items SET data = :data, last_updated = now() WHERE id = :id AND" +
                    " (SELECT project_id FROM dashboard_items item JOIN dashboard ON (dashboard.id = item.dashboard) WHERE item.id = :id AND dashboard.project_id = :project) is not null")
                    .bind("id", item_id)
                    .bind("project", project.project)
                    .bind("data", data).execute();
            return SuccessMessage.success();
        }
    }

    @JsonRequest
    @ApiOperation(value = "List Report")
    @Path("/list")
    public List<Dashboard> list(@Named("user_id") Project project)
    {
        try (Handle handle = dbi.open()) {
            return handle.createQuery("SELECT id, name, refresh_interval, options FROM dashboard WHERE project_id = :project ORDER BY id")
                    .bind("project", project.project).map((i, resultSet, statementContext) -> {
                        Map options = JsonHelper.read(resultSet.getString(4), Map.class);
                        return new Dashboard(resultSet.getInt(1), resultSet.getString(2),
                                resultSet.getObject(3) == null ? null : Duration.ofSeconds(resultSet.getInt(3)),
                                options == null ? null : options);
                    }).list();
        }
    }

    public static class Dashboard
    {
        public final int id;
        public final String name;
        public final Map<String, Object> options;
        public final Duration refresh_interval;

        @JsonCreator
        public Dashboard(int id, String name, Duration refresh_interval, Map<String, Object> options)
        {
            this.id = id;
            this.name = name;
            this.refresh_interval = refresh_interval;
            this.options = options;
        }
    }

    public static class DashboardItem
    {
        public final Integer id;
        public final Map options;
        public final Duration refreshInterval;
        public final String directive;
        public final String name;
        public final Instant lastUpdated;
        public final byte[] data;

        @JsonCreator
        public DashboardItem(
                @JsonProperty("id") Integer id,
                @JsonProperty("name") String name,
                @JsonProperty("directive") String directive,
                @JsonProperty("options") Map options,
                @JsonProperty("refreshInterval") Duration refreshInterval,
                @JsonProperty("data") byte[] data)
        {
            this(id, name, directive, options, refreshInterval, null, data);
        }

        public DashboardItem(Integer id, String name, String directive, Map options, Duration refreshInterval,
                Instant lastUpdated, byte[] data)
        {
            this.id = id;
            this.options = options;
            this.refreshInterval = refreshInterval;
            this.directive = directive;
            this.name = name;
            this.lastUpdated = lastUpdated;
            this.data = data;
        }
    }

    @JsonRequest
    @ApiOperation(value = "Add item to dashboard")
    @Path("/add_item")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage addToDashboard(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("name") String itemName,
            @ApiParam("directive") String directive,
            @ApiParam(value = "refresh_interval", required = false) Duration refreshInterval,
            @ApiParam("options") Map options)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("INSERT INTO dashboard_items (dashboard, name, directive, options, refresh_interval) VALUES (:dashboard, :name, :directive, :options, :refreshInterval)")
                    .bind("project", project.project)
                    .bind("dashboard", dashboard)
                    .bind("name", itemName)
                    .bind("directive", directive)
                    .bind("refreshInterval", Optional.ofNullable(refreshInterval).map(e -> e.getSeconds()).orElse(null))
                    .bind("options", JsonHelper.encode(options)).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Update dashboard items")
    @Path("/update_dashboard_items")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage updateDashboard(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("items") List<DashboardItem> items)
    {
        dbi.inTransaction((handle, transactionStatus) -> {
            Long execute = handle.createQuery("SELECT id FROM dashboard WHERE id = :id AND project_id = :project")
                    .bind("id", dashboard)
                    .bind("project", project.project)
                    .map(LongMapper.FIRST).first();

            if (execute == null) {
                throw new RakamException(HttpResponseStatus.NOT_FOUND);
            }

            for (DashboardItem item : items) {
                // TODO: verify dashboard is in project
                handle.createStatement("UPDATE dashboard_items SET name = :name, directive = :directive, options = :options WHERE id = :id")
                        .bind("id", item.id)
                        .bind("name", item.name)
                        .bind("directive", item.directive)
                        .bind("options", JsonHelper.encode(item.options))
                        .execute();
            }
            return null;
        });
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Update dashboard options")
    @Path("/update_dashboard_options")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage updateDashboardOptions(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("name") String name,
            @ApiParam("refresh_interval") Duration refreshDuration,
            @ApiParam("options") Map<String, Object> options)
    {
        dbi.inTransaction((handle, transactionStatus) -> {
            handle.createStatement("UPDATE dashboard SET options = :options, refresh_interval = :refreshDuration, name = :name WHERE id = :id AND project_id = :project")
                    .bind("id", dashboard)
                    .bind("name", name)
                    .bind("refreshDuration", refreshDuration.getSeconds())
                    .bind("options", JsonHelper.encode(options))
                    .bind("project", project.project)
                    .execute();
            return null;
        });
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Rename dashboard item")
    @Path("/rename_item")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage renameDashboardItem(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("id") int id,
            @ApiParam("name") String name)
    {
        try (Handle handle = dbi.open()) {
            // todo: check project
            handle.createStatement("UPDATE dashboard_items SET name = :name WHERE id = :id")
                    .bind("id", id)
                    .bind("name", name).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Delete dashboard item")
    @Path("/delete_item")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage removeFromDashboard(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("id") int id)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM dashboard_items " +
                    "WHERE dashboard = :dashboard AND id = :id")
                    .bind("project", project.project)
                    .bind("dashboard", dashboard)
                    .bind("id", id).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Delete dashboard item")
    @Path("/delete")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage delete(@Named("user_id") Project project,
            @ApiParam("id") int dashboard)
    {
        try (Handle handle = dbi.open()) {
            int execute = handle.createStatement("DELETE FROM dashboard WHERE id = :id and project_id = :project AND " +
                    "(select count(*) FROM dashboard WHERE project_id = :project) > 1")
                    .bind("id", dashboard).bind("project", project.project).execute();
            if (execute == 0) {
                throw new RakamException("You cannot remove the single dashboard.", BAD_REQUEST);
            }
        }
        return SuccessMessage.success();
    }
}
