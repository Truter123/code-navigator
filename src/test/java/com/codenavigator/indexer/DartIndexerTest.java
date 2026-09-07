package com.codenavigator.indexer;

import com.codenavigator.graph.Edge;
import com.codenavigator.graph.EdgeType;
import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DartIndexerTest {

    private final DartIndexer indexer = new DartIndexer();

    @Test
    void detectsComponent(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("greeting_card.dart");
        Files.writeString(f, "import 'package:flutter/material.dart';\n\n"
            + "final class GreetingCard extends StatelessWidget {\n"
            + "  const GreetingCard({super.key, required this.name});\n"
            + "  final String name;\n\n"
            + "  @override\n"
            + "  Widget build(BuildContext context) => Text('Hello $name');\n"
            + "}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_COMPONENT);
        assertThat(nodes.getFirst().name()).isEqualTo("GreetingCard");
    }

    @Test
    void detectsCommandAndHandler(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("sign_out_command.dart");
        Files.writeString(f, "abstract class Command { const Command(); }\n\n"
            + "final class SignOutCommand extends Command {\n"
            + "  const SignOutCommand();\n"
            + "}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).filteredOn(n -> n.name().equals("SignOutCommand"))
            .extracting("type").containsExactly(NodeType.COMMAND);
    }

    @Test
    void detectsCommandHandler(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("sign_out_command_handler.dart");
        Files.writeString(f, "abstract class CommandHandler<C> { }\n\n"
            + "final class SignOutCommandHandler extends CommandHandler<SignOutCommand> {\n"
            + "  Future<void> handle(SignOutCommand command) async {}\n"
            + "}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).filteredOn(n -> n.name().equals("SignOutCommandHandler"))
            .extracting("type").containsExactly(NodeType.COMMAND_HANDLER);
        // the generic bound on CommandHandler<C> must not itself register a node/edge
        assertThat(nodes).extracting("name").doesNotContain("C");
    }

    @Test
    void detectsQueryAndHandler(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("get_worker_query.dart");
        Files.writeString(f, "abstract class Query<R> { const Query(); }\n"
            + "abstract class QueryHandler<Q, R> { }\n\n"
            + "final class GetWorkerQuery extends Query<String> {\n"
            + "  const GetWorkerQuery();\n"
            + "}\n\n"
            + "final class GetWorkerQueryHandler extends QueryHandler<GetWorkerQuery, String> {\n"
            + "  Future<String> handle(GetWorkerQuery query) async => 'x';\n"
            + "}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).filteredOn(n -> n.name().equals("GetWorkerQuery"))
            .extracting("type").containsExactly(NodeType.QUERY);
        assertThat(nodes).filteredOn(n -> n.name().equals("GetWorkerQueryHandler"))
            .extracting("type").containsExactly(NodeType.QUERY_HANDLER);
    }

    @Test
    void detectsEnum(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("task_status.dart");
        Files.writeString(f, "enum TaskStatus { pending, inProgress, done }\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_ENUM);
        assertThat(nodes.getFirst().name()).isEqualTo("TaskStatus");
    }

    @Test
    void detectsServiceByInterfaceAndBySuffix(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("worker_repository.dart");
        Files.writeString(f, "abstract interface class WorkerRepository {\n"
            + "  Future<void> save(String id);\n"
            + "}\n\n"
            + "final class HttpWorkerRepository implements WorkerRepository {\n"
            + "  @override\n"
            + "  Future<void> save(String id) async {}\n"
            + "}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("name").containsExactlyInAnyOrder("WorkerRepository", "HttpWorkerRepository");
        assertThat(nodes).allMatch(n -> n.type() == NodeType.FE_SERVICE);
    }

    @Test
    void detectsModelAsCatchAll(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("worker.dart");
        Files.writeString(f, "final class Worker {\n"
            + "  const Worker({required this.id, required this.name});\n"
            + "  final String id;\n"
            + "  final String name;\n"
            + "}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("type").containsExactly(NodeType.FE_MODEL);
        assertThat(nodes.getFirst().name()).isEqualTo("Worker");
    }

    @Test
    void privateClassesAreNotIndexed(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("login_page.dart");
        Files.writeString(f, "import 'package:flutter/material.dart';\n\n"
            + "final class LoginPage extends StatefulWidget {\n"
            + "  const LoginPage({super.key});\n\n"
            + "  @override\n"
            + "  State<LoginPage> createState() => _LoginPageState();\n"
            + "}\n\n"
            + "class _LoginPageState extends State<LoginPage> {\n"
            + "  @override\n"
            + "  Widget build(BuildContext context) => const Placeholder();\n"
            + "}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("name").containsExactly("LoginPage");
    }

    @Test
    void fileWithOnlyPrivateSymbolsYieldsNoNodes(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("_internal_helpers.dart");
        Files.writeString(f, "int _clampToByte(int v) => v.clamp(0, 255);\n\n"
            + "class _GridPatternPainter extends CustomPainter {\n"
            + "  @override\n"
            + "  void paint(dynamic canvas, dynamic size) {}\n"
            + "}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).isEmpty();
    }

    @Test
    void docCommentsDoNotProducePhantomNodes(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("authed_client.dart");
        Files.writeString(f, "/// [retryOn401] defaults to true (refresh-then-retry, per the class doc).\n"
            + "/// type/status are enum names as strings; kept raw here.\n"
            + "final class AuthedClient {\n"
            + "  const AuthedClient();\n"
            + "}\n");
        var nodes = indexer.indexFile(f);
        assertThat(nodes).extracting("name").containsExactly("AuthedClient");
    }

    @Test
    void classEdgesForExtendsWithAndImplements(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("premium_worker.dart");
        Files.writeString(f, "class Worker {}\n"
            + "mixin Auditable {}\n"
            + "abstract interface class Describable { String describe(); }\n\n"
            + "final class PremiumWorker extends Worker with Auditable implements Describable {\n"
            + "  @override\n"
            + "  String describe() => 'premium';\n"
            + "}\n");
        var known = Set.of("Worker", "Auditable", "Describable", "PremiumWorker");
        List<Edge> edges = indexer.classEdges(f, known);

        assertThat(edges).anyMatch(e -> e.type() == EdgeType.EXTENDS
            && e.sourceId().equals("PremiumWorker") && e.targetId().equals("Worker"));
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.IMPLEMENTS
            && e.sourceId().equals("PremiumWorker") && e.targetId().equals("Auditable"));
        assertThat(edges).anyMatch(e -> e.type() == EdgeType.IMPLEMENTS
            && e.sourceId().equals("PremiumWorker") && e.targetId().equals("Describable"));
    }

    @Test
    void classEdgesSkipUnknownFrameworkBases(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("session_store.dart");
        Files.writeString(f, "final class SessionStore extends ChangeNotifier {\n"
            + "  void clear() {}\n"
            + "}\n");
        List<Edge> edges = indexer.classEdges(f, Set.of("SessionStore"));
        assertThat(edges).isEmpty();
    }
}
