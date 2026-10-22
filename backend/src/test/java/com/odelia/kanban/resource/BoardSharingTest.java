package com.odelia.kanban.resource;

import com.odelia.kanban.entity.Board;
import com.odelia.kanban.entity.BoardList;
import com.odelia.kanban.entity.BoardMember;
import com.odelia.kanban.entity.Card;
import com.odelia.kanban.repository.BoardListRepository;
import com.odelia.kanban.repository.BoardMemberRepository;
import com.odelia.kanban.repository.BoardRepository;
import com.odelia.kanban.repository.CardRepository;
import com.odelia.kanban.security.BoardAccess;
import com.odelia.kanban.security.CurrentUser;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class BoardSharingTest {

    private final TestUser user = new TestUser();
    private final Map<Long, Board> boardRows = new HashMap<>();
    private final Map<Long, BoardList> columnRows = new HashMap<>();
    private final Map<Long, Card> cardRows = new HashMap<>();
    private final List<BoardMember> memberRows = new ArrayList<>();
    private BoardResource resource;
    private BoardListResource columns;
    private CardResource cards;

    @BeforeEach
    void setUp() throws Exception {
        boardRows.put(1L, board(1, "Sprint", "alice"));
        boardRows.put(2L, board(2, "Private", "dave"));
        columnRows.put(10L, column(10, 1, "To do"));
        columnRows.put(11L, column(11, 1, "Done"));
        columnRows.put(20L, column(20, 2, "Private"));
        Card card = new Card();
        card.setId(100L);
        card.setListId(10);
        card.setTitle("Task");
        cardRows.put(100L, card);
        memberRows.add(new BoardMember(1, "bob"));

        BoardRepository boardRepository = repository(BoardRepository.class, (method, args) -> switch (method) {
            case "findById" -> Optional.ofNullable(boardRows.get(args[0]));
            case "findByOwnerOrderByNameAsc" -> boardRows.values().stream()
                    .filter(b -> b.getOwner().equals(args[0])).sorted(Comparator.comparing(Board::getName)).toList();
            case "findAllSortedByName" -> boardRows.values().stream().sorted(Comparator.comparing(Board::getName)).toList();
            case "save" -> args[0];
            case "delete" -> boardRows.remove(((Board) args[0]).getId());
            default -> throw new UnsupportedOperationException(method);
        });
        BoardMemberRepository memberRepository = repository(BoardMemberRepository.class, (method, args) -> switch (method) {
            case "countByBoardIdAndUsername" -> memberRows.stream()
                    .filter(m -> m.getBoardId() == (long) args[0] && m.getUsername().equals(args[1])).count();
            case "findByBoardIdAndUsername" -> memberRows.stream()
                    .filter(m -> m.getBoardId() == (long) args[0] && m.getUsername().equals(args[1])).findFirst();
            case "findByBoardIdOrderByUsernameAsc" -> memberRows.stream()
                    .filter(m -> m.getBoardId() == (long) args[0])
                    .sorted(Comparator.comparing(BoardMember::getUsername)).toList();
            case "findByUsername" -> memberRows.stream().filter(m -> m.getUsername().equals(args[0])).toList();
            case "insert" -> {
                memberRows.add((BoardMember) args[0]);
                yield args[0];
            }
            case "delete" -> memberRows.remove((BoardMember) args[0]);
            case "deleteByBoardId" -> memberRows.removeIf(m -> m.getBoardId() == (long) args[0]);
            default -> throw new UnsupportedOperationException(method);
        });
        BoardListRepository listRepository = repository(BoardListRepository.class, (method, args) -> switch (method) {
            case "findById" -> Optional.ofNullable(columnRows.get(args[0]));
            case "findByBoardIdOrderByPositionAsc" -> columnRows.values().stream()
                    .filter(c -> c.getBoardId() == (long) args[0]).toList();
            case "save" -> args[0];
            case "insert" -> {
                BoardList added = (BoardList) args[0];
                added.setId(30L);
                columnRows.put(30L, added);
                yield added;
            }
            case "delete" -> columnRows.remove(((BoardList) args[0]).getId());
            case "deleteByBoardId" -> columnRows.values().removeIf(c -> c.getBoardId() == (long) args[0]);
            default -> throw new UnsupportedOperationException(method);
        });
        CardRepository cardRepository = repository(CardRepository.class, (method, args) -> switch (method) {
            case "findById" -> Optional.ofNullable(cardRows.get(args[0]));
            case "findByListIdOrderByPositionAsc" -> cardRows.values().stream()
                    .filter(c -> c.getListId() == (long) args[0]).sorted(Comparator.comparingInt(Card::getPosition)).toList();
            case "countByListId" -> cardRows.values().stream().filter(c -> c.getListId() == (long) args[0]).count();
            case "save" -> args[0];
            case "saveAll" -> args[0];
            case "insert" -> {
                Card added = (Card) args[0];
                added.setId(101L);
                cardRows.put(101L, added);
                yield added;
            }
            case "delete" -> cardRows.remove(((Card) args[0]).getId());
            case "deleteByListId" -> cardRows.values().removeIf(c -> c.getListId() == (long) args[0]);
            default -> throw new UnsupportedOperationException(method);
        });
        UriInfo uriInfo = repository(UriInfo.class, (method, args) -> UriBuilder.fromUri("http://localhost/api/boards/1/members"));
        BoardAccess access = new BoardAccess();
        inject(access, "currentUser", user);
        inject(access, "boards", boardRepository);
        inject(access, "lists", listRepository);
        inject(access, "members", memberRepository);

        resource = new BoardResource();
        resource.currentUser = user;
        resource.access = access;
        resource.boards = boardRepository;
        resource.lists = listRepository;
        resource.members = memberRepository;
        resource.cards = cardRepository;
        resource.uriInfo = uriInfo;
        columns = new BoardListResource();
        columns.access = access;
        columns.lists = listRepository;
        columns.cards = cardRepository;
        columns.uriInfo = uriInfo;
        cards = new CardResource();
        cards.access = access;
        cards.lists = listRepository;
        cards.cards = cardRepository;
    }

    @Test
    void memberSeesOwnedAndSharedBoardsSortedWithoutDuplicates() {
        user.username = "bob";
        boardRows.put(3L, board(3, "Alpha", "bob"));
        memberRows.add(new BoardMember(3, "bob"));
        assertEquals(List.of(3L, 1L), resource.listBoards().stream().map(Board::getId).toList());
    }

    @Test
    void memberCanReadBoardMembersColumnsAndCards() {
        user.username = "bob";
        assertEquals("Sprint", resource.getBoard(1).getName());
        assertEquals("bob", resource.listMembers(1).getFirst().getUsername());
        assertEquals(2, resource.listColumns(1).size());
        assertEquals("To do", columns.getColumn(10).getName());
        assertEquals(1, columns.listCards(10).size());
        assertEquals("Task", cards.getCard(100).getTitle());
    }

    @Test
    void memberCanCreateEditMoveAndDeleteCardsAndColumns() {
        user.username = "bob";
        assertEquals(201, resource.addColumn(1, new BoardList(1, "Review", 2)).getStatus());
        assertEquals("Finished", columns.updateColumn(11, new BoardList(1, "Finished", 1)).getName());
        Card draft = new Card();
        draft.setTitle("New task");
        assertEquals(201, columns.addCard(10, draft).getStatus());
        draft.setTitle("Edited");
        assertEquals("Edited", cards.updateCard(101, draft).getTitle());
        assertEquals(11, cards.move(101, new MoveCommand(11, 0)).getListId());
        assertEquals(204, cards.deleteCard(101).getStatus());
        assertEquals(204, columns.deleteColumn(30).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = { "rename", "delete", "add", "remove", "removeSelf" })
    void memberCannotPerformOwnerActions(String action) {
        user.username = "bob";
        ForbiddenException error = assertThrows(ForbiddenException.class, () -> ownerAction(action));
        assertEquals(403, error.getResponse().getStatus());
        assertEquals("Sprint", boardRows.get(1L).getName());
        assertEquals(1, memberRows.size());
    }

    @Test
    void ownerCanAddTrimmedUsernameAndRemoveMember() {
        assertEquals(201, resource.addMember(1, new MemberCommand("  dave  ")).getStatus());
        assertTrue(memberRows.stream().anyMatch(m -> m.getUsername().equals("dave")));
        assertEquals(204, resource.removeMember(1, "dave").getStatus());
        assertEquals(1, memberRows.size());
    }

    @ParameterizedTest
    @ValueSource(strings = { "alice", "bob" })
    void ownerAndDuplicateMembershipAreRejectedWith400(String username) {
        assertEquals(400, assertThrows(BadRequestException.class,
                () -> resource.addMember(1, new MemberCommand(username))).getResponse().getStatus());
        assertEquals(1, memberRows.size());
    }

    @Test
    void ownerCannotBeRemoved() {
        assertEquals(400, assertThrows(BadRequestException.class,
                () -> resource.removeMember(1, "alice")).getResponse().getStatus());
        assertEquals("alice", resource.getBoard(1).getOwner());
    }

    @Test
    void removalRevokesAccessOnNextRequestWithTheSameUser() {
        user.username = "bob";
        resource.getBoard(1);
        user.username = "alice";
        resource.removeMember(1, "bob");
        user.username = "bob";
        assertThrows(ForbiddenException.class, () -> resource.getBoard(1));
        assertThrows(ForbiddenException.class, () -> cards.getCard(100));
        assertThrows(ForbiddenException.class, () -> resource.listMembers(1));
        assertTrue(resource.listBoards().isEmpty());
    }

    @Test
    void unrelatedUserHasNoAccessThroughAnyResource() {
        user.username = "eve";
        assertTrue(resource.listBoards().isEmpty());
        assertThrows(ForbiddenException.class, () -> resource.getBoard(1));
        assertThrows(ForbiddenException.class, () -> resource.listMembers(1));
        assertThrows(ForbiddenException.class, () -> resource.listColumns(1));
        assertThrows(ForbiddenException.class, () -> columns.getColumn(10));
        assertThrows(ForbiddenException.class, () -> cards.getCard(100));
        assertThrows(ForbiddenException.class, () -> resource.addMember(1, new MemberCommand("eve")));
    }

    @Test
    void memberCannotMoveCardToAnInaccessibleBoard() {
        user.username = "bob";
        assertThrows(ForbiddenException.class, () -> cards.move(100, new MoveCommand(20, 0)));
        assertEquals(10, cards.getCard(100).getListId());
    }

    @Test
    void memberCannotMoveACardFromAnInaccessibleBoard() {
        cardRows.get(100L).setListId(20);
        user.username = "bob";
        assertThrows(ForbiddenException.class, () -> cards.move(100, new MoveCommand(10, 0)));
        assertEquals(20, cardRows.get(100L).getListId());
    }

    @Test
    void adminCanSeeAndManageBoardsWithoutMembership() {
        user.username = "carol";
        user.admin = true;
        assertEquals(2, resource.listBoards().size());
        assertEquals("Private", resource.getBoard(2).getName());
        assertEquals(201, resource.addMember(2, new MemberCommand("bob")).getStatus());
        assertEquals(204, resource.removeMember(2, "bob").getStatus());
        assertEquals("Renamed", resource.updateBoard(1, board(1, "Renamed", "carol")).getName());
        assertEquals("alice", boardRows.get(1L).getOwner());
        assertEquals(204, resource.deleteBoard(1).getStatus());
    }

    @Test
    void deletingBoardAlsoDeletesItsMembershipsAndContents() {
        assertEquals(204, resource.deleteBoard(1).getStatus());
        assertFalse(boardRows.containsKey(1L));
        assertTrue(memberRows.isEmpty());
        assertFalse(columnRows.containsKey(10L));
        assertTrue(cardRows.isEmpty());
    }

    @Test
    void orphanedColumnsAndCardsAreDenied() {
        columnRows.get(10L).setBoardId(999);
        assertThrows(ForbiddenException.class, () -> columns.getColumn(10));
        assertThrows(ForbiddenException.class, () -> cards.getCard(100));
    }

    private void ownerAction(String action) {
        switch (action) {
            case "rename" -> resource.updateBoard(1, board(1, "Changed", "bob"));
            case "delete" -> resource.deleteBoard(1);
            case "add" -> resource.addMember(1, new MemberCommand("carol"));
            case "remove" -> resource.removeMember(1, "alice");
            case "removeSelf" -> resource.removeMember(1, "bob");
            default -> throw new IllegalArgumentException(action);
        }
    }

    private static Board board(long id, String name, String owner) {
        Board board = new Board(name, owner);
        board.setId(id);
        return board;
    }

    private static BoardList column(long id, long boardId, String name) {
        BoardList column = new BoardList(boardId, name, 0);
        column.setId(id);
        return column;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T repository(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> handler.apply(method.getName(), args)));
    }

    private static class TestUser extends CurrentUser {
        String username = "alice";
        boolean admin;

        @Override
        public String name() {
            return username;
        }

        @Override
        public boolean isAdmin() {
            return admin;
        }
    }
}
