package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.ConnectedBoardBuilder.connectedBoard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.process.PlatformEnvironment;
import ch.fmartin.symphony.trello.testsupport.BoundedWait;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloBoardServer;
import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures;
import ch.fmartin.symphony.trello.time.ApplicationClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CodexBoardSessionTest {
    private static final String DOTENV_KEY = "dotenv-key-sentinel-603";
    private static final String DOTENV_TOKEN = "dotenv-token-sentinel-603";
    private static final String SHELL_KEY = "shell-key-sentinel-603";
    private static final String SHELL_TOKEN = "shell-token-sentinel-603";
    private static final List<String> CREDENTIAL_SENTINELS = List.of(DOTENV_KEY, DOTENV_TOKEN, SHELL_KEY, SHELL_TOKEN);
    private static final String QUEUE_BOARD = "Symphony Work Queue";
    private static final String OPS_BOARD = "Ops Board";
    /// A board name with every character that `cmd.exe` would change in a quoted argument.
    private static final String CMD_CHANGED_BOARD_NAME = "Ops \"Q%A!\" <board> & | ^\r\nnext";
    /// When to look again whether Codex can add session instructions without replacing the user's.
    private static final LocalDate DEVELOPER_INSTRUCTIONS_REVIEW_DATE = LocalDate.of(2027, 4, 30);

    @TempDir
    Path tempDir;

    private final ObjectMapper json = new ObjectMapper();
    private final List<StartedCodex> startedCodex = new CopyOnWriteArrayList<>();
    private final AtomicInteger codexRuns = new AtomicInteger();
    private Path configDir;
    private FakeTrelloBoardServer queueTrello;
    private FakeTrelloBoardServer opsTrello;
    private ConnectedBoard queueBoard;
    private ConnectedBoard opsBoard;
    private FakeCommandRunner commands;

    @BeforeEach
    void setUp() throws Exception {
        configDir = Files.createDirectories(tempDir.resolve("config"));
        Files.writeString(
                configDir.resolve(".env"),
                "TRELLO_API_KEY=" + DOTENV_KEY + "\nTRELLO_API_TOKEN=" + DOTENV_TOKEN + "\n");
        queueTrello = boardWithRecommendedLists(new FakeTrelloBoardServer("board-1", "SYNTH001", QUEUE_BOARD))
                .withCard("card-1", "Card0001", "Add snapshot tests", "list-ready")
                .start();
        opsTrello = boardWithRecommendedLists(new FakeTrelloBoardServer("board-2", "SYNTH002", OPS_BOARD))
                .start();
        queueBoard = connectedBoard(writeWorkflow("WORKFLOW.queue.md", queueTrello, "board-1", "$TRELLO_API_KEY"))
                .withBoardId("board-1")
                .withBoardKey("SYNTH001")
                .withBoardName(QUEUE_BOARD)
                .withBoardUrl("https://trello.com/b/SYNTH001/symphony-work-queue")
                .withEnvPath(configDir.resolve(".env"))
                .build();
        opsBoard = connectedBoard(writeWorkflow("WORKFLOW.ops.md", opsTrello, "board-2", "$TRELLO_API_KEY"))
                .withBoardId("board-2")
                .withBoardKey("SYNTH002")
                .withBoardName(OPS_BOARD)
                .withBoardUrl("https://trello.com/b/SYNTH002/ops-board")
                .withEnvPath(configDir.resolve(".env"))
                .withDangerFullAccess(true)
                .build();
        commands = FakeCodexMain.installedAndSignedIn();
    }

    @AfterEach
    void tearDown() {
        queueTrello.close();
        opsTrello.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ops Board", "ops board", "board-2", "SYNTH002", "https://trello.com/b/SYNTH002/ops-board"})
    void boardSelectorUsesTheConnectedBoardSelectors(String selector) throws Exception {
        // given
        saveManifest(queueBoard, opsBoard);

        // when
        int exitCode =
                session(Map.of(), false, "exit:0").run(request(Optional.of(selector), Optional.empty()), terminal());

        // then
        assertThat(exitCode).isZero();
        assertThat(developerInstructions(report())).contains("Selected board: Ops Board, short link SYNTH002");
    }

    @Test
    void workflowSelectorUsesTheNormalizedWorkflowPath() throws Exception {
        // given
        saveManifest(queueBoard, opsBoard);
        Path relativeWorkflow = tempDir.relativize(opsBoard.workflowPath());
        Path workflowSelector = tempDir.resolve("config").resolve("..").resolve(relativeWorkflow);

        // when
        int exitCode = session(Map.of(), false, "exit:0")
                .run(request(Optional.empty(), Optional.of(workflowSelector)), terminal());

        // then
        assertThat(exitCode).isZero();
        assertThat(developerInstructions(report())).contains("Selected board: Ops Board");
    }

    @Test
    void rejectsBoardAndWorkflowTogether() {
        // given
        CodexBoardSession session = session(Map.of(), false, "exit:0");

        // when
        Throwable thrown = catchThrowable(
                () -> session.run(request(Optional.of(OPS_BOARD), Optional.of(opsBoard.workflowPath())), terminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> assertThat(e.code())
                .isEqualTo("setup_worker_selection_conflict"));
        assertThat(codexRuns).hasValue(0);
    }

    @Test
    void rejectsAWorkflowThatIsNotConnected() throws Exception {
        // given
        saveManifest(queueBoard);

        // when
        Throwable thrown = catchThrowable(() -> session(Map.of(), false, "exit:0")
                .run(request(Optional.empty(), Optional.of(opsBoard.workflowPath())), terminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> assertThat(e.code())
                .isEqualTo("setup_codex_workflow_not_connected"));
        assertThat(codexRuns).hasValue(0);
    }

    @Test
    void rejectsAnAmbiguousBoardSelector() throws Exception {
        // given
        ConnectedBoard sameName =
                ConnectedBoardBuilder.from(opsBoard).withBoardName(QUEUE_BOARD).build();
        saveManifest(queueBoard, sameName);

        // when
        Throwable thrown = catchThrowable(() -> session(Map.of(), false, "exit:0")
                .run(request(Optional.of(QUEUE_BOARD), Optional.empty()), terminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> assertThat(e.code())
                .isEqualTo("setup_worker_board_ambiguous"));
        assertThat(codexRuns).hasValue(0);
    }

    @Test
    void failsWithConnectHintWhenNoBoardIsConnected() {
        // given

        // when
        Throwable thrown =
                catchThrowable(() -> session(Map.of(), true, "exit:0").run(noSelector(), terminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> {
            assertThat(e.code()).isEqualTo("setup_codex_no_connected_board");
            assertThat(e.getMessage()).contains("symphony-trello setup-local");
        });
        assertThat(codexRuns).hasValue(0);
    }

    @Test
    void selectsTheOnlyConnectedBoardWithoutAPicker() throws Exception {
        // given
        saveManifest(queueBoard);
        var terminal = terminal();

        // when
        int exitCode = session(Map.of(), false, "exit:0").run(noSelector(), terminal);

        // then
        assertThat(exitCode).isZero();
        assertThat(terminal.stdout()).doesNotContain("Choose the Trello board");
        assertThat(developerInstructions(report())).contains("Selected board: Symphony Work Queue");
    }

    @Test
    void requiresASelectorWhenSeveralBoardsAreConnectedWithoutATerminal() throws Exception {
        // given
        saveManifest(queueBoard, opsBoard);

        // when
        Throwable thrown =
                catchThrowable(() -> session(Map.of(), false, "exit:0").run(noSelector(), terminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> {
            assertThat(e.code()).isEqualTo("setup_codex_board_required");
            assertThat(e.getMessage()).contains("--board", "--workflow");
        });
        assertThat(codexRuns).hasValue(0);
        assertThat(queueTrello.requests()).isEmpty();
    }

    @Test
    void pickerListsEligibleBoardsAndStartsTheChosenOne() throws Exception {
        // given
        saveManifest(queueBoard, opsBoard);
        var terminal = terminal("7", "two", "2");

        // when
        int exitCode = session(Map.of(), true, "exit:0").run(noSelector(), terminal);

        // then
        assertThat(exitCode).isZero();
        assertThat(terminal.stdout())
                .contains(
                        "Choose the Trello board for this Codex session:",
                        "  1. \"Symphony Work Queue\" (SYNTH001, WORKFLOW.queue.md)",
                        "  2. \"Ops Board\" (SYNTH002, WORKFLOW.ops.md)",
                        "Enter a number from 1 to 2, or press Enter to cancel.")
                .doesNotContain(DOTENV_KEY, DOTENV_TOKEN);
        assertThat(developerInstructions(report())).contains("Selected board: Ops Board");
    }

    @Test
    void cancellingThePickerStartsNothingAndContactsNoOne() throws Exception {
        // given
        saveManifest(queueBoard, opsBoard);
        var terminal = terminal("");

        // when
        int exitCode = session(Map.of(), true, "exit:0").run(noSelector(), terminal);

        // then
        assertThat(exitCode).isEqualTo(CodexBoardSession.USER_CANCELLED_EXIT_CODE);
        assertThat(terminal.stdout()).contains("Cancelled. Codex was not started.");
        assertThat(codexRuns).hasValue(0);
        assertThat(queueTrello.requests()).isEmpty();
        assertThat(opsTrello.requests()).isEmpty();
    }

    @Test
    void pickerSkipsBoardsWithoutAUsableWorkflow() throws Exception {
        // given
        Files.delete(opsBoard.workflowPath());
        ConnectedBoard invalid = connectedBoard(
                        Files.writeString(configDir.resolve("WORKFLOW.broken.md"), "no front matter"))
                .withBoardId("board-3")
                .withBoardKey("BROKEN03")
                .withBoardName("Broken Board")
                .withEnvPath(configDir.resolve(".env"))
                .build();
        saveManifest(queueBoard, opsBoard, invalid);
        var terminal = terminal();

        // when
        int exitCode = session(Map.of(), true, "exit:0").run(noSelector(), terminal);

        // then
        assertThat(exitCode).isZero();
        assertThat(terminal.stdout())
                .contains(
                        "Skipped connected board \"Ops Board\" (SYNTH002, WORKFLOW.ops.md): Invalid workflow configuration",
                        "Skipped connected board \"Broken Board\" (BROKEN03, WORKFLOW.broken.md): Invalid workflow configuration")
                .doesNotContain("Choose the Trello board");
        assertThat(developerInstructions(report())).contains("Selected board: Symphony Work Queue");
    }

    @Test
    void reportsMissingCodexBeforeContactingTrello() throws Exception {
        // given
        saveManifest(queueBoard);
        commands = new FakeCommandRunner();

        // when
        Throwable thrown =
                catchThrowable(() -> session(Map.of(), false, "exit:0").run(noSelector(), terminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> {
            assertThat(e.code()).isEqualTo("setup_codex_missing");
            assertThat(e.getMessage()).contains("codex --version");
        });
        assertThat(codexRuns).hasValue(0);
        assertThat(queueTrello.requests()).isEmpty();
    }

    @Test
    void reportsMissingCodexLoginWithoutPrintingAuthDetails() throws Exception {
        // given
        saveManifest(queueBoard);
        commands = new FakeCommandRunner()
                .returns(0, "codex-cli 0.160.0", "codex", "--version")
                .returns(1, "Not logged in. Credentials file: /home/user/.codex/auth.json", "codex", "login", "status");

        // when
        Throwable thrown =
                catchThrowable(() -> session(Map.of(), false, "exit:0").run(noSelector(), terminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> {
            assertThat(e.code()).isEqualTo("setup_codex_auth_required");
            assertThat(e.getMessage()).contains("codex login").doesNotContain("auth.json");
        });
        assertThat(codexRuns).hasValue(0);
        assertThat(queueTrello.requests()).isEmpty();
    }

    @Test
    void shellCredentialsWinOverTheBoardCredentialFile() throws Exception {
        // given
        saveManifest(queueBoard);

        // when
        session(Map.of("TRELLO_API_KEY", SHELL_KEY, "TRELLO_API_TOKEN", SHELL_TOKEN), false, "exit:0")
                .run(noSelector(), terminal());

        // then
        assertThat(queueTrello.requests()).isNotEmpty().allSatisfy(request -> assertThat(request.authorization())
                .contains(SHELL_KEY, SHELL_TOKEN)
                .doesNotContain(DOTENV_KEY));
    }

    @Test
    void usesTheBoardsOwnCredentialFile() throws Exception {
        // given
        Path boardEnv = Files.writeString(
                configDir.resolve(".env.queue"),
                "TRELLO_API_KEY=" + SHELL_KEY + "\nTRELLO_API_TOKEN=" + SHELL_TOKEN + "\n");
        saveManifest(
                ConnectedBoardBuilder.from(queueBoard).withEnvPath(boardEnv).build());

        // when
        session(Map.of(), false, "exit:0").run(noSelector(), terminal());

        // then
        assertThat(queueTrello.requests()).isNotEmpty().allSatisfy(request -> assertThat(request.authorization())
                .contains(SHELL_KEY, SHELL_TOKEN));
    }

    @Test
    void reportsMissingTrelloCredentialsForTheSession() throws Exception {
        // given
        Files.writeString(configDir.resolve(".env"), "TRELLO_API_TOKEN=" + DOTENV_TOKEN + "\n");
        saveManifest(queueBoard);

        // when
        Throwable thrown =
                catchThrowable(() -> session(Map.of(), false, "exit:0").run(noSelector(), terminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> {
            assertThat(e.code()).isEqualTo("setup_worker_missing_api_key");
            assertThat(e.getMessage()).isEqualTo("Missing Trello API key for the Codex board session.");
        });
        assertThat(codexRuns).hasValue(0);
    }

    @Test
    void reportsRejectedTrelloCredentialsWithoutPrintingThem() throws Exception {
        // given
        saveManifest(queueBoard);
        queueTrello.rejectingCredentials();
        var terminal = terminal();

        // when
        Throwable thrown =
                catchThrowable(() -> session(Map.of(), false, "exit:0").run(noSelector(), terminal));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> {
            assertThat(e.code()).isEqualTo("trello_auth_failed");
            assertThat(e.getMessage()).doesNotContain(CREDENTIAL_SENTINELS);
        });
        assertThat(List.of(terminal.stdout(), terminal.stderr()))
                .allSatisfy(text -> assertThat(text).doesNotContain(CREDENTIAL_SENTINELS));
        assertThat(codexRuns).hasValue(0);
    }

    @Test
    void launchesCodexWithStructuredArgumentsAndTheCallersTerminal() throws Exception {
        // given
        saveManifest(opsBoard);

        // when
        int exitCode = session(Map.of(), false, "exit:7").run(noSelector(), terminal());

        // then
        assertThat(exitCode).as("the command exits with Codex's exit status").isEqualTo(7);
        StartedCodex started = startedCodex.getFirst();
        assertThat(started.inheritsTerminal())
                .as("Codex must read and write the caller's terminal directly")
                .isTrue();
        JsonNode report = report();
        assertThat(report.path("cwd").asText())
                .isEqualTo(Path.of("").toAbsolutePath().toString());
        assertThat(report.path("args"))
                .extracting(JsonNode::asText)
                .satisfiesExactly(
                        flag -> assertThat(flag).isEqualTo("-c"),
                        url -> assertThat(url)
                                .matches("mcp_servers\\.symphony_trello\\.url=http://127\\.0\\.0\\.1:\\d+/mcp"),
                        flag -> assertThat(flag).isEqualTo("-c"),
                        token -> assertThat(token)
                                .isEqualTo("mcp_servers.symphony_trello.bearer_token_env_var="
                                        + CodexBoardSession.SESSION_TOKEN_ENVIRONMENT),
                        flag -> assertThat(flag).isEqualTo("-c"),
                        instructions ->
                                assertThat(instructions).startsWith("developer_instructions=Symphony for Trello"));
        assertThat(developerInstructions(report))
                .as("Codex knows the enabled Trello tools before the first request")
                .contains("Trello tools the workflow enables: trello_board_overview, trello_list_cards,")
                .contains("trello_move_card", "trello_archive_card");
        assertThat(report.path("args").toString())
                .as("the board's dangerFullAccess worker setting must not change the interactive sandbox")
                .doesNotContain("sandbox", "danger", "approval", "--full-auto", "--yolo");
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the cmd.exe stand-in is a Bash script")
    @Test
    void startsTheNpmCodexShimThroughTheCommandInterpreterWithTheBoardContextUnchanged() throws Exception {
        // given
        saveManifest(ConnectedBoardBuilder.from(opsBoard)
                .withBoardName(CMD_CHANGED_BOARD_NAME)
                .build());
        Path codexShim = Files.writeString(
                Files.createDirectories(tempDir.resolve("npm")).resolve("codex.CMD"), "");
        Path interpreterArguments = tempDir.resolve("interpreter-arguments.txt");

        // when
        int exitCode = simulatedWindowsSession(codexShim.getParent(), interpreterArguments)
                .run(noSelector(), terminal());

        // then
        assertThat(exitCode).isZero();
        assertThat(Files.readAllLines(interpreterArguments))
                .satisfiesExactly(
                        flag -> assertThat(flag).isEqualTo("/d"),
                        flag -> assertThat(flag).isEqualTo("/s"),
                        flag -> assertThat(flag).isEqualTo("/c"),
                        commandLine -> assertThat(commandLine)
                                .as("every argument quoted once, with the board context unchanged")
                                .matches(Pattern.quote("\"\"" + codexShim + "\" \"-c\" \"")
                                        + "mcp_servers\\.symphony_trello\\.url=http://127\\.0\\.0\\.1:\\d+/mcp"
                                        + Pattern.quote("\" \"-c\" \"mcp_servers.symphony_trello.bearer_token_env_var="
                                                + CodexBoardSession.SESSION_TOKEN_ENVIRONMENT
                                                + "\" \"-c\" \"developer_instructions=Symphony for Trello")
                                        + "[^\"]*"
                                        + Pattern.quote("Selected board: Ops Q A board next, short link SYNTH002")
                                        + "[^\"]*\"\""));
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows does not allow the shim directory name")
    @Test
    void refusesToStartTheNpmCodexShimFromAPathThatTheCommandInterpreterWouldChange() throws Exception {
        // given
        saveManifest(opsBoard);
        Path shimDirectory = Files.createDirectories(tempDir.resolve("npm & co"));
        // The PATHEXT spelling, because this host's file system, unlike Windows, is case sensitive.
        Files.writeString(shimDirectory.resolve("codex.CMD"), "");
        Path interpreterArguments = tempDir.resolve("interpreter-arguments.txt");

        // when
        Throwable thrown = catchThrowable(() ->
                simulatedWindowsSession(shimDirectory, interpreterArguments).run(noSelector(), terminal()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, e -> assertThat(e.code())
                .isEqualTo("setup_codex_missing"));
        assertThat(codexRuns).as("no process starts with a changed argument").hasValue(0);
        assertThat(interpreterArguments).doesNotExist();
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    void startsTheNpmCodexShimOnWindowsWithTheBoardContextUnchanged() throws Exception {
        // given
        saveManifest(ConnectedBoardBuilder.from(opsBoard)
                .withBoardName(CMD_CHANGED_BOARD_NAME)
                .build());
        Path shimDirectory = Files.createDirectories(tempDir.resolve("npm prefix"));
        // Same structure as npm's shim. The class path goes through CLASSPATH because cmd.exe limits
        // the command line to 8191 characters.
        Files.writeString(
                shimDirectory.resolve("codex.cmd"),
                "@ECHO off\r\n\"" + Path.of(System.getProperty("java.home"), "bin", "java.exe") + "\" "
                        + FakeCodexMain.class.getName() + " %*\r\n");
        Map<String, String> environment = new HashMap<>(environment(Map.of()));
        environment.keySet().removeIf(name -> name.equalsIgnoreCase("PATH"));
        environment.putAll(Map.of(
                "PATH",
                shimDirectory.toString(),
                "PATHEXT",
                WindowsShimFixtures.NPM_PATHEXT,
                "CLASSPATH",
                System.getProperty("java.class.path")));

        // when
        int exitCode = session(new PlatformEnvironment(environment, true), List.of("codex"), false, "mcp")
                .run(noSelector(), terminal());

        // then
        assertThat(exitCode).isZero();
        JsonNode report = report();
        assertThat(developerInstructions(report))
                .startsWith("developer_instructions=Symphony for Trello")
                .contains("Selected board: Ops Q A board next, short link SYNTH002");
        assertThat(report.path("mcp")
                        .path(2)
                        .path("result")
                        .path("content")
                        .path(0)
                        .path("text")
                        .asText())
                .as("the MCP endpoint and token variable reached Codex intact, so the board overview answers")
                .contains("\"short_link\":\"SYNTH002\"");
    }

    @Test
    void developerInstructionsReplacementIsRecheckedForIssue789() {
        // given
        LocalDate today = LocalDate.ofInstant(ApplicationClock.systemUtc().instant(), ZoneOffset.UTC);

        // when
        boolean reviewDue = today.isAfter(DEVELOPER_INSTRUCTIONS_REVIEW_DATE);

        // then
        assertThat(reviewDue)
                .as(
                        """
                        The board session replaces the user's own Codex developer_instructions because Codex \
                        had no option that adds to them (#789). Check the current Codex CLI for one. If it \
                        exists, pass the board context that way and delete this test. If not, comment the \
                        checked Codex version on #789 and move DEVELOPER_INSTRUCTIONS_REVIEW_DATE six months.""")
                .isFalse();
    }

    @Test
    void keepsTrelloCredentialsOutOfCodexAndTheOutput() throws Exception {
        // given
        Path workflow = writeWorkflow("WORKFLOW.custom.md", queueTrello, "board-1", "$CUSTOM_TRELLO_KEY");
        saveManifest(
                ConnectedBoardBuilder.from(queueBoard)
                        .withEnvPath(configDir.resolve(".env.missing"))
                        .build(),
                connectedBoard(workflow)
                        .withBoardName("Custom")
                        .withBoardId("board-9")
                        .build());
        Map<String, String> environment = Map.of(
                "CUSTOM_TRELLO_KEY",
                SHELL_KEY,
                "TRELLO_API_KEY",
                DOTENV_KEY,
                "TRELLO_API_TOKEN",
                SHELL_TOKEN,
                LocalEnvironment.DOTENV_PATH_ENV,
                configDir.resolve(".env").toString());
        var terminal = terminal();

        // when
        int exitCode =
                session(environment, false, "mcp").run(request(Optional.of("Custom"), Optional.empty()), terminal);

        // then
        assertThat(exitCode).isZero();
        JsonNode report = report();
        assertThat(report.path("environment").has(CodexBoardSession.SESSION_TOKEN_ENVIRONMENT))
                .as("Codex receives the session bearer token through its environment")
                .isTrue();
        assertThat(report.path("environment").has("CUSTOM_TRELLO_KEY"))
                .as("custom credential variables named by the workflow are removed")
                .isFalse();
        assertThat(report.path("environment").has(LocalEnvironment.DOTENV_PATH_ENV))
                .as("Codex is not told where the Trello credential file is")
                .isFalse();
        assertThat(List.of(
                        report.path("args").toString(),
                        report.path("environment").toString(),
                        report.path("mcp").toString(),
                        terminal.stdout(),
                        terminal.stderr()))
                .allSatisfy(text -> assertThat(text).doesNotContain(CREDENTIAL_SENTINELS));
        assertThat(report.path("mcp")
                        .path(3)
                        .path("result")
                        .path("content")
                        .path(0)
                        .path("text")
                        .asText())
                .as("Codex can use the board tools through the session token")
                .contains("Add snapshot tests");
        assertThat(queueTrello.requests())
                .allSatisfy(request -> assertThat(request.authorization()).contains(SHELL_KEY, SHELL_TOKEN));
        assertThat(filesWrittenOutsideCredentialFiles())
                .as("no file the session or Codex wrote may hold Trello credentials")
                .isNotEmpty()
                .allSatisfy(file -> assertThat(file).content().doesNotContain(CREDENTIAL_SENTINELS));
    }

    @Test
    void stopsTheBoardToolsWhenCodexExits() throws Exception {
        // given
        saveManifest(queueBoard);

        // when
        session(Map.of(), false, "exit:0").run(noSelector(), terminal());

        // then
        assertBoardToolsStopped(mcpEndpoint(report()));
    }

    @Test
    void interruptionStopsCodexAndTheBoardTools() throws Exception {
        // given
        saveManifest(queueBoard);
        CodexBoardSession session = session(Map.of(), false, "wait");
        var exitCode = new AtomicReference<Integer>();
        var failure = new AtomicReference<Throwable>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                exitCode.set(session.run(noSelector(), terminal()));
            } catch (IOException | RuntimeException e) {
                failure.set(e);
            }
        });
        BoundedWait.until(
                Duration.ofSeconds(30),
                () -> Files.isRegularFile(reportPath()) && reportPath().toFile().length() > 0,
                "fake Codex reports that it started");
        JsonNode report = report();
        long codexPid = report.path("pid").asLong();

        // when
        caller.interrupt();
        caller.join(Duration.ofSeconds(30));

        // then
        assertThat(failure.get()).isNull();
        assertThat(exitCode.get()).isEqualTo(CodexBoardSession.USER_CANCELLED_EXIT_CODE);
        assertThat(ProcessHandle.of(codexPid).filter(ProcessHandle::isAlive))
                .as("the Codex child must not outlive the session")
                .isEmpty();
        assertBoardToolsStopped(mcpEndpoint(report));
    }

    /// Every regular file under the test directory except the `.env` files that hold credentials on
    /// purpose, including the fake Codex report and output files.
    private List<Path> filesWrittenOutsideCredentialFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!file.getFileName().toString().startsWith(".env")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /// The session's MCP server must refuse connections once the session has ended.
    private static void assertBoardToolsStopped(URI endpoint) {
        assertThatThrownBy(() -> HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(endpoint)
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString()))
                .as("the board tool server must be closed")
                .isInstanceOf(ConnectException.class);
    }

    private CodexBoardSession session(Map<String, String> overrides, boolean interactive, String mode) {
        return session(
                PlatformEnvironment.of(environment(overrides), System.getProperty("os.name")),
                FakeCodexMain.command(),
                interactive,
                mode);
    }

    /// A session on a simulated Windows host that finds the npm shim in `shimDirectory` and whose
    /// `cmd.exe` stand-in records its arguments in `interpreterArguments`.
    private CodexBoardSession simulatedWindowsSession(Path shimDirectory, Path interpreterArguments)
            throws IOException {
        Path commandInterpreter = Files.writeString(
                tempDir.resolve("cmd.exe"),
                """
                #!/bin/bash
                printf '%s\\n' "$@" > "$CAPTURE"
                """);
        assertThat(commandInterpreter.toFile().setExecutable(true))
                .as("the cmd.exe stand-in must be executable")
                .isTrue();
        Map<String, String> environment = new HashMap<>(environment(Map.of()));
        environment.putAll(Map.of(
                "PATH",
                shimDirectory.toString(),
                "PATHEXT",
                WindowsShimFixtures.NPM_PATHEXT,
                "ComSpec",
                commandInterpreter.toString(),
                "CAPTURE",
                interpreterArguments.toString()));
        return session(new PlatformEnvironment(environment, true), List.of("codex"), false, "exit:0");
    }

    private CodexBoardSession session(
            PlatformEnvironment environment, List<String> codexCommand, boolean interactive, String mode) {
        return new CodexBoardSession(
                environment,
                new WorkflowConfigEditor(),
                new LocalWorkerManager(environment.variables()),
                commands,
                codexCommand,
                builder -> {
                    codexRuns.incrementAndGet();
                    startedCodex.add(new StartedCodex(builder.redirectInput() == ProcessBuilder.Redirect.INHERIT
                            && builder.redirectOutput() == ProcessBuilder.Redirect.INHERIT
                            && builder.redirectError() == ProcessBuilder.Redirect.INHERIT));
                    builder.environment()
                            .put(FakeCodexMain.REPORT_ENVIRONMENT, reportPath().toString());
                    builder.environment().put(FakeCodexMain.MODE_ENVIRONMENT, mode);
                    builder.redirectInput(ProcessBuilder.Redirect.PIPE);
                    builder.redirectOutput(tempDir.resolve("codex.out").toFile());
                    builder.redirectError(tempDir.resolve("codex.err").toFile());
                    return builder.start();
                },
                () -> interactive,
                json);
    }

    private CodexSessionRequest request(Optional<String> board, Optional<Path> workflow) {
        return new CodexSessionRequest(
                board,
                workflow,
                Optional.of(tempDir.resolve("app")),
                Optional.of(configDir),
                Optional.of(tempDir.resolve("workspaces")),
                Optional.of(tempDir.resolve("state")));
    }

    private CodexSessionRequest noSelector() {
        return request(Optional.empty(), Optional.empty());
    }

    private static RecordingTerminal terminal(String... input) {
        return new RecordingTerminal(input);
    }

    private void saveManifest(ConnectedBoard... boards) throws IOException {
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(boards)));
    }

    private Path reportPath() {
        return tempDir.resolve("codex-report.json");
    }

    private JsonNode report() throws IOException {
        return json.readTree(Files.readString(reportPath()));
    }

    private static String developerInstructions(JsonNode report) {
        for (JsonNode argument : report.path("args")) {
            if (argument.asText().startsWith("developer_instructions=")) {
                return argument.asText();
            }
        }
        throw new AssertionError("Codex was started without developer instructions: " + report.path("args"));
    }

    /// The real process environment without any Trello credentials, plus the test's values, so the
    /// fake Codex child still has the operating-system variables a JVM needs.
    private static Map<String, String> environment(Map<String, String> overrides) {
        Map<String, String> environment = new HashMap<>();
        System.getenv().forEach((name, value) -> {
            if (!name.startsWith("TRELLO_")) {
                environment.put(name, value);
            }
        });
        environment.putAll(overrides);
        return environment;
    }

    private static URI mcpEndpoint(JsonNode report) {
        Map<String, String> overrides = new HashMap<>();
        report.path("args").forEach(argument -> {
            String text = argument.asText();
            int separator = text.indexOf('=');
            if (separator > 0) {
                overrides.put(text.substring(0, separator), text.substring(separator + 1));
            }
        });
        return URI.create(overrides.get("mcp_servers.symphony_trello.url"));
    }

    private static FakeTrelloBoardServer boardWithRecommendedLists(FakeTrelloBoardServer board) {
        return board.withList("list-inbox", "Inbox")
                .withList("list-ready", "Ready for Codex")
                .withList("list-progress", "In Progress")
                .withList("list-blocked", "Blocked")
                .withList("list-review", "Human Review")
                .withList("list-done", "Done");
    }

    private Path writeWorkflow(String fileName, FakeTrelloBoardServer trello, String boardId, String apiKeyReference)
            throws IOException {
        return Files.writeString(
                configDir.resolve(fileName),
                """
                ---
                tracker:
                  kind: trello
                  endpoint: "%s"
                  api_key: "%s"
                  api_token: $TRELLO_API_TOKEN
                  board_id: "%s"
                  active_states:
                    - Ready for Codex
                    - In Progress
                  in_progress_state: In Progress
                  terminal_states:
                    - Done
                codex:
                  command: codex app-server
                trello_tools:
                  enabled: true
                  allow_writes: true
                  allowed_move_list_names:
                    - In Progress
                    - Human Review
                    - Done
                ---
                ## Trello List Routing

                - "Ready for Codex": queued work; move the card to "In Progress" before active implementation.
                - "Blocked": blocked work. Symphony does not dispatch it while this list is not configured as active.
                - "Human Review": human review. Do not code from this list unless a human moves the card back.
                """
                        .formatted(trello.endpoint(), apiKeyReference, boardId));
    }

    private record StartedCodex(boolean inheritsTerminal) {}
}
