package com.odelia.kanban.security;

import com.odelia.kanban.entity.Board;
import com.odelia.kanban.entity.BoardList;
import com.odelia.kanban.entity.Card;
import com.odelia.kanban.repository.BoardListRepository;
import com.odelia.kanban.repository.BoardMemberRepository;
import com.odelia.kanban.repository.BoardRepository;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;

/**
 * Authorization for owners, members and administrators of a board.
 *
 * <p>{@code @RolesAllowed} alone only answers "is this a signed-in Kanban
 * user?", which is not enough: every user has the {@code user} role, so without
 * these checks anyone could read someone else's board by guessing its id. The
 * coarse role check and this fine-grained one are complementary.</p>
 *
 * <p>Columns and cards inherit collaborator access from their board. Renaming
 * or deleting the board and changing its members remain owner-only actions.</p>
 */
@RequestScoped
public class BoardAccess {

    @Inject
    CurrentUser currentUser;

    @Inject
    BoardRepository boards;

    @Inject
    BoardListRepository lists;

    @Inject
    BoardMemberRepository members;

    /** Owners, members and admins may read a board and work on its contents. */
    public void requireBoardAccess(Board board) {
        if (!currentUser.isAdmin() && !currentUser.name().equals(board.getOwner())
                && members.countByBoardIdAndUsername(board.getId(), currentUser.name()) == 0) {
            throw new ForbiddenException("You do not have access to board " + board.getId());
        }
    }

    /** Membership never grants permission to manage the board itself. */
    public void requireBoardOwner(Board board) {
        if (!currentUser.isAdmin() && !currentUser.name().equals(board.getOwner())) {
            throw new ForbiddenException("Board " + board.getId() + " belongs to someone else");
        }
    }

    /** The same check, reached through the column's board. */
    public void requireColumnAccess(BoardList column) {
        requireBoardAccess(boardOf(column));
    }

    /** The same check, reached through the card's column and then its board. */
    public void requireCardAccess(Card card) {
        requireColumnAccess(columnOf(card));
    }

    private Board boardOf(BoardList column) {
        return boards.findById(column.getBoardId())
                     .orElseThrow(() -> orphan("column " + column.getId()));
    }

    private BoardList columnOf(Card card) {
        return lists.findById(card.getListId())
                    .orElseThrow(() -> orphan("card " + card.getId()));
    }

    /** With no board to check against, nobody can be shown to own the row: deny. */
    private ForbiddenException orphan(String what) {
        return new ForbiddenException("Cannot establish who owns " + what);
    }
}
