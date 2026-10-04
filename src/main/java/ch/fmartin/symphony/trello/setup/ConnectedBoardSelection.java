package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Optional;

/// The `--board`/`--workflow` conflict rule shared by every command that accepts both selectors, so
/// lifecycle commands and the interactive Codex session report the same error. The lookup rules
/// themselves live in [ConnectedBoardManifest#selectBoard] and [ConnectedBoardManifest#selectWorkflow].
final class ConnectedBoardSelection {
    private ConnectedBoardSelection() {}

    static void rejectConflictingSelectors(Optional<String> board, Optional<Path> workflow) {
        if (board.isPresent() && workflow.isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_selection_conflict", "--board and --workflow cannot be used together.");
        }
    }
}
