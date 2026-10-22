package com.odelia.kanban.repository;

import com.odelia.kanban.entity.Board;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Repository;

import java.util.List;

/**
 * Jakarta Data repository for {@link Board}.
 *
 * <p>{@code CrudRepository} already provides {@code save}, {@code insert},
 * {@code update}, {@code findById}, {@code findAll}, {@code delete} and
 * {@code deleteById}; only the extra queries are declared here.</p>
 *
 * <p>{@code dataStore} is the JNDI name of the {@code <dataSource>} declared in
 * {@code server.xml}. Liberty derives the persistence unit - and the database
 * schema - from the entity types used by the repository.</p>
 */
@Repository(dataStore = "jdbc/kanban")
public interface BoardRepository extends CrudRepository<Board, Long> {

    @Find
    @OrderBy("name")
    List<Board> findAllSortedByName();

    /**
     * The boards owned by one user; shared boards are added by the resource, where
     * {@code owner} is the {@code preferred_username} taken from the JWT.
     */
    List<Board> findByOwnerOrderByNameAsc(String owner);
}
