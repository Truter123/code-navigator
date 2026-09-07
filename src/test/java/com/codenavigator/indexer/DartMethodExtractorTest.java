package com.codenavigator.indexer;

import com.codenavigator.graph.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DartMethodExtractorTest {

    private final DartIndexer indexer = new DartIndexer();
    private final DartMethodExtractor extractor = new DartMethodExtractor();

    @Test
    void extractsHandleMethodFromCommandHandler(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("sign_out_command_handler.dart");
        String content = "abstract class Command { const Command(); }\n"
            + "abstract class CommandHandler<C> { }\n\n"
            + "final class SignOutCommand extends Command { const SignOutCommand(); }\n\n"
            + "final class SignOutCommandHandler extends CommandHandler<SignOutCommand> {\n"
            + "  SignOutCommandHandler({required this._tokens});\n"
            + "  final TokenStore _tokens;\n\n"
            + "  Future<void> handle(SignOutCommand command) async {\n"
            + "    await _tokens.clear();\n"
            + "  }\n"
            + "}\n";
        Files.writeString(f, content);

        var nodes = indexer.indexFile(f);
        var methods = extractor.extract(content, nodes, f.toString(), 0L);

        assertThat(methods).extracting(m -> m.node().name()).contains("handle");
        var handle = methods.stream().filter(m -> m.node().name().equals("handle")).findFirst().orElseThrow();
        assertThat(handle.ownerName()).isEqualTo("SignOutCommandHandler");
        assertThat(handle.node().type()).isEqualTo(NodeType.METHOD);
        assertThat(handle.node().id()).isEqualTo("SignOutCommandHandler#handle(SignOutCommand)");
    }

    @Test
    void resolvesFieldCallToKnownType(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("session_ops.dart");
        String content = "final class TokenStore {\n"
            + "  Future<void> clear() async {}\n"
            + "}\n\n"
            + "final class SessionOps {\n"
            + "  SessionOps({required this._tokens});\n"
            + "  final TokenStore _tokens;\n\n"
            + "  Future<void> signOut() async {\n"
            + "    await _tokens.clear();\n"
            + "  }\n"
            + "}\n";
        Files.writeString(f, content);

        var nodes = indexer.indexFile(f);
        var methods = extractor.extract(content, nodes, f.toString(), 0L);
        var knownTypes = Set.of("TokenStore", "SessionOps");

        var edges = extractor.resolveCalls(methods, knownTypes);
        assertThat(edges).anyMatch(e ->
            e.sourceId().equals("SessionOps#signOut()") && e.targetId().equals("TokenStore#clear()"));
    }

    @Test
    void resolvesSiblingCallOnSameClass(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("greeter.dart");
        String content = "final class Greeter {\n"
            + "  String greet(String name) {\n"
            + "    return _wrap(name);\n"
            + "  }\n\n"
            + "  String _wrap(String name) {\n"
            + "    return 'Hello $name';\n"
            + "  }\n"
            + "}\n";
        Files.writeString(f, content);

        var nodes = indexer.indexFile(f);
        var methods = extractor.extract(content, nodes, f.toString(), 0L);
        var edges = extractor.resolveCalls(methods, Set.of("Greeter"));

        assertThat(edges).anyMatch(e ->
            e.sourceId().equals("Greeter#greet(String)") && e.targetId().equals("Greeter#_wrap(String)"));
    }

    @Test
    void namedParametersDoNotCorruptTheMethodId(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("token_store.dart");
        String content = "final class TokenStore {\n"
            + "  Future<void> save({\n"
            + "    required String accessToken,\n"
            + "    required String refreshToken,\n"
            + "  }) async {}\n"
            + "}\n";
        Files.writeString(f, content);

        var nodes = indexer.indexFile(f);
        var methods = extractor.extract(content, nodes, f.toString(), 0L);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().node().id()).isEqualTo("TokenStore#save(String,String)");
    }

    @Test
    void nonClassLikeNodesYieldNoMethods(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("task_status.dart");
        String content = "enum TaskStatus { pending, done }\n";
        Files.writeString(f, content);

        var nodes = indexer.indexFile(f);
        List<DartMethodExtractor.DartMethod> methods = extractor.extract(content, nodes, f.toString(), 0L);
        assertThat(methods).isEmpty();
    }
}
