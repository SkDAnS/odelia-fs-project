import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { BoardListComponent } from './board-list.component';

describe('BoardListComponent sharing permissions', () => {
  let http: HttpTestingController;
  const auth = { username: signal('bob'), isAdmin: signal(false) };
  const boards = [
    { id: 1, name: 'Shared sprint', owner: 'alice' },
    { id: 2, name: 'My board', owner: 'bob' }
  ];

  beforeEach(async () => {
    auth.username.set('bob');
    auth.isAdmin.set(false);
    await TestBed.configureTestingModule({
      imports: [BoardListComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function loadBoards() {
    const fixture = TestBed.createComponent(BoardListComponent);
    fixture.detectChanges();
    http.expectOne('/api/boards').flush(boards);
    fixture.detectChanges();
    return fixture;
  }

  it('shows shared boards and only offers deletion for an owned board', () => {
    const fixture = loadBoards();
    const rows: NodeListOf<HTMLElement> = fixture.nativeElement.querySelectorAll('li.board');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Shared sprint');
    expect(rows[0].querySelector('button.danger')).toBeNull();
    expect(rows[1].querySelector('button.danger')).not.toBeNull();
  });

  it('does not send a delete request when a member calls the method directly', () => {
    const fixture = loadBoards();
    const confirm = spyOn(window, 'confirm');
    fixture.componentInstance.deleteBoard(boards[0]);
    expect(confirm).not.toHaveBeenCalled();
    http.expectNone(req => req.method === 'DELETE');
  });

  it('allows an administrator to delete any board', () => {
    auth.username.set('carol');
    auth.isAdmin.set(true);
    const fixture = loadBoards();
    expect(fixture.nativeElement.querySelectorAll('button.danger').length).toBe(2);
    spyOn(window, 'confirm').and.returnValue(true);
    fixture.componentInstance.deleteBoard(boards[0]);
    const request = http.expectOne('/api/boards/1');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    expect(fixture.componentInstance.boards().length).toBe(1);
  });
});
