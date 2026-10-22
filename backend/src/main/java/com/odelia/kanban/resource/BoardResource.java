package com.odelia.kanban.resource;

import com.odelia.kanban.entity.Board;
import com.odelia.kanban.entity.BoardList;
import com.odelia.kanban.entity.BoardMember;
import com.odelia.kanban.repository.BoardListRepository;
import com.odelia.kanban.repository.BoardMemberRepository;
import com.odelia.kanban.repository.BoardRepository;
import com.odelia.kanban.repository.CardRepository;
import com.odelia.kanban.security.BoardAccess;
import com.odelia.kanban.security.CurrentUser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD over boards, plus the lists (columns) they contain.
 *
 * <p>Every endpoint needs a valid Keycloak token: {@code @RolesAllowed}
 * turns an anonymous call into a 401 and a token without the {@code user} role
 * into a 403. On top of that a board belongs to the person who created it - the
 * {@code preferred_username} from the JWT - so the listing is filtered and the
 * per-board endpoints go through {@link BoardAccess}.</p>
 */
@Path("/boards")
@RequestScoped
@RolesAllowed({ CurrentUser.USER, CurrentUser.ADMIN })
@SecurityRequirement(name = "bearerAuth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BoardResource {

    private static final List<String> DEFAULT_LISTS = List.of("To do", "Doing", "Done");

    @Inject
    CurrentUser currentUser;

    @Inject
    BoardAccess access;

    @Inject
    BoardRepository boards;

    @Inject
    BoardListRepository lists;

    @Inject
    CardRepository cards;

    @Inject
    BoardMemberRepository members;

    @Context
    UriInfo uriInfo;

    /** Owned and shared boards, without duplicates; admins see every board. */
    @GET
    @Operation(operationId = "getBoards")
    public List<Board> listBoards() {
        if (currentUser.isAdmin()) {
            return boards.findAllSortedByName();
        }
        String username = currentUser.name();
        Map<Long, Board> visible = new LinkedHashMap<>();
        for (Board board : boards.findByOwnerOrderByNameAsc(username)) {
            visible.put(board.getId(), board);
        }
        for (BoardMember member : members.findByUsername(username)) {
            boards.findById(member.getBoardId()).ifPresent(board -> visible.put(board.getId(), board));
        }
        return visible.values().stream().sorted(Comparator.comparing(Board::getName)).toList();
    }

    @GET
    @Path("/{id}")
    public Board getBoard(@PathParam("id") long id) {
        return accessibleBoard(id);
    }

    /**
     * Creates a board and seeds it with the three usual columns, so a new board
     * is immediately usable from the UI. The owner always comes from the token:
     * an {@code owner} sent by the client is ignored.
     */
    @POST
    @Transactional
    public Response createBoard(@Valid Board board) {
        board.setId(null);
        board.setCreatedAt(LocalDateTime.now());
        board.setOwner(currentUser.name());
        Board created = boards.insert(board);

        for (int i = 0; i < DEFAULT_LISTS.size(); i++) {
            lists.insert(new BoardList(created.getId(), DEFAULT_LISTS.get(i), i));
        }

        return Response.created(uriInfo.getAbsolutePathBuilder().path(String.valueOf(created.getId())).build())
                       .entity(created)
                       .build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Board updateBoard(@PathParam("id") long id, @Valid Board board) {
        Board existing = accessibleBoard(id);
        access.requireBoardOwner(existing);
        existing.setName(board.getName());
        return boards.save(existing);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteBoard(@PathParam("id") long id) {
        Board existing = accessibleBoard(id);
        access.requireBoardOwner(existing);
        for (BoardList column : lists.findByBoardIdOrderByPositionAsc(id)) {
            cards.deleteByListId(column.getId());
        }
        lists.deleteByBoardId(id);
        members.deleteByBoardId(id);
        boards.delete(existing);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/lists")
    public List<BoardList> listColumns(@PathParam("id") long id) {
        accessibleBoard(id);
        return lists.findByBoardIdOrderByPositionAsc(id);
    }

    @POST
    @Path("/{id}/lists")
    @Transactional
    public Response addColumn(@PathParam("id") long id, @Valid BoardList column) {
        accessibleBoard(id);
        column.setId(null);
        column.setBoardId(id);
        BoardList created = lists.insert(column);
        return Response.created(uriInfo.getBaseUriBuilder().path("lists").path(String.valueOf(created.getId())).build())
                       .entity(created)
                       .build();
    }

    @GET
    @Path("/{id}/members")
    public List<BoardMember> listMembers(@PathParam("id") long id) {
        accessibleBoard(id);
        return members.findByBoardIdOrderByUsernameAsc(id);
    }

    @POST
    @Path("/{id}/members")
    @Transactional
    public Response addMember(@PathParam("id") long id, @NotNull @Valid MemberCommand command) {
        Board board = accessibleBoard(id);
        access.requireBoardOwner(board);
        String username = command.username().trim();
        if (username.equals(board.getOwner())) {
            throw new BadRequestException("The owner already has access to this board");
        }
        if (members.countByBoardIdAndUsername(id, username) > 0) {
            throw new BadRequestException("This user is already a member of the board");
        }
        BoardMember created = members.insert(new BoardMember(id, username));
        return Response.created(uriInfo.getAbsolutePathBuilder().path(username).build())
                       .entity(created)
                       .build();
    }

    @DELETE
    @Path("/{id}/members/{username}")
    @Transactional
    public Response removeMember(@PathParam("id") long id, @PathParam("username") String username) {
        Board board = accessibleBoard(id);
        access.requireBoardOwner(board);
        if (username.equals(board.getOwner())) {
            throw new BadRequestException("The owner cannot be removed from the board");
        }
        BoardMember member = members.findByBoardIdAndUsername(id, username).orElseThrow(
                () -> new WebApplicationException("No such member on this board", Response.Status.NOT_FOUND));
        members.delete(member);
        return Response.noContent().build();
    }

    /** Missing boards return 404; existing boards require collaborator access. */
    private Board accessibleBoard(long id) {
        Board board = boards.findById(id).orElseThrow(() -> notFound(id));
        access.requireBoardAccess(board);
        return board;
    }

    private WebApplicationException notFound(long id) {
        return new WebApplicationException("No board with id " + id, Response.Status.NOT_FOUND);
    }
}
