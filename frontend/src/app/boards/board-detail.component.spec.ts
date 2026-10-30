import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';
import { signal } from '@angular/core';

import { BoardDetailComponent } from './board-detail.component';
import { AuthService } from '../auth/auth.service';

describe('BoardDetailComponent', () => {
  let http: HttpTestingController;
  let auth: { username: ReturnType<typeof signal<string>>; isAdmin: ReturnType<typeof signal<boolean>> };

  beforeEach(async () => {
    auth = { username: signal('alice'), isAdmin: signal(false) };
    await TestBed.configureTestingModule({
      imports: [BoardDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: new Map([['id', '7']]) } } }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the board and its columns on init', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });
    http.expectOne('/api/boards/7/members').flush([]);

    const listsReq = http.expectOne('/api/boards/7/lists');
    listsReq.flush([{ id: 1, boardId: 7, name: 'To do', position: 0 }]);

    http.expectOne('/api/lists/1/cards').flush([
      { id: 10, listId: 1, title: 'First card', position: 0 }
    ]);

    expect(fixture.componentInstance.board()?.name).toBe('Sprint 1');
    expect(fixture.componentInstance.cardsFor(1).length).toBe(1);
  });

  it('PATCHes the card when it is dropped into another column', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });
    http.expectOne('/api/boards/7/members').flush([]);
    http.expectOne('/api/boards/7/lists').flush([
      { id: 1, boardId: 7, name: 'To do', position: 0 },
      { id: 2, boardId: 7, name: 'Doing', position: 1 }
    ]);
    http.expectOne('/api/lists/1/cards').flush([{ id: 10, listId: 1, title: 'Card', position: 0 }]);
    http.expectOne('/api/lists/2/cards').flush([]);

    const card = component.cardsFor(1)[0];
    component.drop({
      item: { data: card },
      container: { data: { id: 2 } },
      previousContainer: { data: { id: 1 } },
      previousIndex: 0,
      currentIndex: 0
    } as never);

    const move = http.expectOne('/api/cards/10/move');
    expect(move.request.method).toBe('PATCH');
    expect(move.request.body).toEqual({ targetListId: 2, position: 0 });
    move.flush({ id: 10, listId: 2, title: 'Card', position: 0 });

    expect(component.cardsFor(1).length).toBe(0);
    expect(component.cardsFor(2).length).toBe(1);
  });

  it('PUTs the card and updates it in place when the inline editor is saved', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });
    http.expectOne('/api/boards/7/members').flush([]);
    http.expectOne('/api/boards/7/lists').flush([
      { id: 1, boardId: 7, name: 'To do', position: 0 }
    ]);
    http.expectOne('/api/lists/1/cards').flush([
      { id: 10, listId: 1, title: 'Card', position: 0 }
    ]);

    const card = component.cardsFor(1)[0];
    component.startEdit(card);
    expect(component.editingCardId()).toBe(10);

    component.editDraft.title = 'Card renamed';
    component.editDraft.description = 'Now with details';
    component.saveEdit(card);

    const put = http.expectOne('/api/cards/10');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ title: 'Card renamed', description: 'Now with details' });
    put.flush({ id: 10, listId: 1, title: 'Card renamed', description: 'Now with details', position: 0 });

    expect(component.editingCardId()).toBeNull();
    expect(component.cardsFor(1)[0].description).toBe('Now with details');
  });

  it('opens and closes the "add a card" composer one column at a time', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });
    http.expectOne('/api/boards/7/members').flush([]);
    http.expectOne('/api/boards/7/lists').flush([
      { id: 1, boardId: 7, name: 'To do', position: 0 }
    ]);
    http.expectOne('/api/lists/1/cards').flush([]);

    expect(component.composingColumnId()).toBeNull();

    component.startComposing(1);
    expect(component.composingColumnId()).toBe(1);

    component.cancelComposing();
    expect(component.composingColumnId()).toBeNull();
  });

  it('POSTs title and description from the "add a card" form', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });
    http.expectOne('/api/boards/7/members').flush([]);
    http.expectOne('/api/boards/7/lists').flush([
      { id: 1, boardId: 7, name: 'To do', position: 0 }
    ]);
    http.expectOne('/api/lists/1/cards').flush([]);

    const column = component.columns()[0];
    const draft = component.draftFor(1);
    draft.title = 'New card';
    draft.description = 'With a description';
    component.addCard(column);

    const post = http.expectOne('/api/lists/1/cards');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ title: 'New card', description: 'With a description' });
    post.flush({ id: 20, listId: 1, title: 'New card', description: 'With a description', position: 0 });

    expect(component.cardsFor(1).length).toBe(1);
    expect(draft.title).toBe('');
    expect(draft.description).toBe('');
  });

  function sharedBoard() {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1', owner: 'alice' });
    http.expectOne('/api/boards/7/lists').flush([]);
    http.expectOne('/api/boards/7/members').flush([{ boardId: 7, username: 'bob' }]);
    fixture.detectChanges();
    return fixture;
  }

  it('shows the members and lets the owner add a trimmed username', () => {
    const fixture = sharedBoard();
    const component = fixture.componentInstance;
    expect(fixture.nativeElement.querySelector('.member-list').textContent).toContain('bob');
    expect(fixture.nativeElement.querySelector('.new-member')).not.toBeNull();
    component.newMemberUsername = '  dave  ';
    component.addMember();
    const post = http.expectOne('/api/boards/7/members');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ username: 'dave' });
    expect(component.savingMembers()).toBeTrue();
    post.flush({ boardId: 7, username: 'dave' });
    expect(component.members().map(m => m.username)).toEqual(['bob', 'dave']);
    expect(component.newMemberUsername).toBe('');
    expect(component.savingMembers()).toBeFalse();
  });

  it('removes a member only after the server confirms the deletion', () => {
    const component = sharedBoard().componentInstance;
    component.removeMember(component.members()[0]);
    const request = http.expectOne('/api/boards/7/members/bob');
    expect(request.request.method).toBe('DELETE');
    expect(component.members().length).toBe(1);
    request.flush(null);
    expect(component.members()).toEqual([]);
  });

  it('shows members but hides management controls from a non-owner', () => {
    auth.username.set('bob');
    const fixture = sharedBoard();
    const component = fixture.componentInstance;
    expect(fixture.nativeElement.querySelector('.member-list').textContent).toContain('bob');
    expect(fixture.nativeElement.querySelector('.new-member')).toBeNull();
    expect(fixture.nativeElement.querySelector('.member-remove')).toBeNull();
    component.newMemberUsername = 'carol';
    component.addMember();
    component.removeMember(component.members()[0]);
    http.expectNone(req => req.method !== 'GET');
  });

  it('keeps membership controls available to an administrator', () => {
    auth.username.set('carol');
    auth.isAdmin.set(true);
    const fixture = sharedBoard();
    expect(fixture.nativeElement.querySelector('.new-member')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.member-remove')).not.toBeNull();
  });

  it('shows a readable error for a duplicate member without changing the list', () => {
    const fixture = sharedBoard();
    const component = fixture.componentInstance;
    spyOn(console, 'error');
    component.newMemberUsername = 'bob';
    component.addMember();
    http.expectOne('/api/boards/7/members').flush(null, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.error').textContent).toContain('cannot be added twice');
    expect(component.members().length).toBe(1);
    expect(component.savingMembers()).toBeFalse();
  });

  it('shows a readable 403 when the server refuses a membership change', () => {
    const component = sharedBoard().componentInstance;
    spyOn(console, 'error');
    component.removeMember(component.members()[0]);
    http.expectOne('/api/boards/7/members/bob').flush(null, { status: 403, statusText: 'Forbidden' });
    expect(component.error()).toContain('Only the owner or an administrator');
    expect(component.members().length).toBe(1);
    expect(component.savingMembers()).toBeFalse();
  });

  it('clears the previous board when a reload is denied after membership removal', () => {
    const component = sharedBoard().componentInstance;
    spyOn(console, 'error');
    component.reload();
    const lists = http.expectOne('/api/boards/7/lists');
    const members = http.expectOne('/api/boards/7/members');
    http.expectOne('/api/boards/7').flush(null, { status: 403, statusText: 'Forbidden' });
    expect(lists.cancelled).toBeTrue();
    expect(members.cancelled).toBeTrue();
    expect(component.board()).toBeNull();
    expect(component.members()).toEqual([]);
    expect(component.columns()).toEqual([]);
    expect(component.loading()).toBeFalse();
    expect(component.error()).toContain('Could not load the board');
  });
});
