# Board sharing

## Comment for the pull request

**Binôme : Anas et Jan.**

`BoardAccess` distinguishes two permissions. `requireBoardAccess` grants access
to the owner, an administrator, or a user recorded in `BoardMember`. The
existing column and card checks walk back to the parent board and use this
collaborator permission. `requireBoardOwner` grants access only to the owner
or an administrator. Renaming or deleting the board and adding or removing
members use this stricter check. Listing members remains available to every
collaborator, as required by R3 and F2.

Ownership remains in `Board.owner`; it is never represented by a membership
row. The new table has a unique constraint on `(BOARD_ID, USERNAME)`. The add
endpoint rejects an existing member and the owner with HTTP 400, and the
remove endpoint also rejects the owner's username. The board list combines
owned boards and memberships, removes duplicates and sorts by name.

One edge case is a member trying to remove themselves or another member.
The owner check runs before the membership lookup or modification, so the
request receives HTTP 403 and no membership is changed. After an authorized
removal, access is checked against the repository again on the next request;
an existing JWT does not preserve the removed membership. Moving a card also
checks access to both the source and destination boards.

The Angular view shows the member list to all collaborators. Management
controls appear only for owners and administrators, with readable errors
when the API refuses a change. The board list applies the same rule to its
Delete buttons. These UI checks complement the server authorization.

## Automated checks

Verification completed: 19 backend tests, 20 Angular tests, a production
Angular build and a Maven WAR build passed. A separate local Liberty,
PostgreSQL and Keycloak instance also passed 55 HTTP checks using the demo
accounts, including invalid input, permissions and revocation. Database
queries confirmed membership and column cleanup after board deletion.

Use Java 21 and Node 22.12 or later (within the versions in
`frontend/package.json`).

```sh
cd backend
mvn test
mvn package
```

```sh
cd frontend
npm ci
npm run build
npm test
```

The backend tests exercise the resource methods with the real `BoardAccess`
and in-memory repository doubles. They cover collaborator operations,
owner-only operations, admins, unrelated users, duplicate memberships,
revocation, deletion cleanup and both ends of a card move. Angular tests
cover the visible controls, membership requests, API errors and shared-board
deletion restrictions.

## Manual verification

Start the services using the root README, then use the test accounts from
`keycloak/README.md`:

1. As `alice`, create a board and add `bob`. Add `bob` again: expect 400 and
   no duplicate. Adding or removing `alice` also returns 400.
2. As `bob`, reload the board list. Open Alice's board, inspect its members,
   then create, edit, move and delete cards. The member form, Remove buttons
   and board Delete button must be absent.
3. With Bob's token, check that renaming/deleting the board or adding/removing
   members returns 403. Reading the member list must still succeed.
4. With Bob's token, verify column creation, update and deletion through the
   existing API, including moving a card to a column on a board Bob cannot
   access (403).
5. As `alice`, remove `bob`. His next board, column, card and member-list
   request must return 403; the board disappears when he reloads his list.
6. As `carol` (admin), verify access and management without membership.
7. Delete a board as its owner and verify its memberships and contents are
   removed as well.
