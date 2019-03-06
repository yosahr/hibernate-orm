/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.metamodel.model.domain.internal.collection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;

import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.model.domain.spi.CollectionIndex;
import org.hibernate.metamodel.model.domain.spi.PersistentCollectionDescriptor;
import org.hibernate.metamodel.model.relational.spi.Column;
import org.hibernate.metamodel.model.relational.spi.Table;
import org.hibernate.sql.SqlExpressableType;
import org.hibernate.sql.ast.Clause;
import org.hibernate.sql.ast.tree.spi.expression.LiteralParameter;
import org.hibernate.sql.ast.tree.spi.from.TableReference;
import org.hibernate.sql.exec.SqlExecLogger;
import org.hibernate.sql.exec.internal.JdbcParameterBindingsImpl;
import org.hibernate.sql.exec.spi.BasicExecutionContext;
import org.hibernate.sql.exec.spi.JdbcMutation;
import org.hibernate.sql.exec.spi.JdbcMutationExecutor;
import org.hibernate.sql.exec.spi.JdbcParameter;
import org.hibernate.internal.log.LoggingHelper;

import org.jboss.logging.Logger;

/**
 * @author Steve Ebersole
 */
public abstract class AbstractCreationExecutor implements CollectionCreationExecutor {
	private static final Logger log = Logger.getLogger( AbstractCreationExecutor.class );

	private final PersistentCollectionDescriptor collectionDescriptor;

	private final JdbcMutation creationOperation;
	private final Map<Column, JdbcParameter> jdbcParameterMap;

	public AbstractCreationExecutor(
			PersistentCollectionDescriptor collectionDescriptor,
			Table dmlTargetTable,
			SessionFactoryImplementor sessionFactory) {
		this.collectionDescriptor = collectionDescriptor;

		final Map<Column, JdbcParameter> jdbcParameterMap = new HashMap<>();

		this.creationOperation = generateCreationOperation(
				new TableReference( dmlTargetTable, null, false ),
				sessionFactory,
				jdbcParameterMap::put
		);

		this.jdbcParameterMap = jdbcParameterMap;
	}

	public PersistentCollectionDescriptor getCollectionDescriptor() {
		return collectionDescriptor;
	}

	protected JdbcMutation getCreationOperation(){
		return creationOperation;
	}

	@SuppressWarnings("WeakerAccess")
	protected abstract JdbcMutation generateCreationOperation(
			TableReference dmlTableRef,
			SessionFactoryImplementor sessionFactory,
			BiConsumer<Column,JdbcParameter> columnConsumer);

	@Override
	public void execute(
			PersistentCollection collection,
			Object key,
			SharedSessionContractImplementor session) {
		if ( key == null ) {
			key = collection.getKey();
		}

		assert key != null;

		final JdbcParameterBindingsImpl jdbcParameterBindings = new JdbcParameterBindingsImpl();
		final BasicExecutionContext executionContext = new BasicExecutionContext( session, jdbcParameterBindings );

		final Iterator entries = collection.entries( collectionDescriptor );

		if ( ! entries.hasNext() ) {
			SqlExecLogger.INSTANCE.debugf(
					"Collection was empty - nothing to (re)create : %s",
					LoggingHelper.toLoggableString( collectionDescriptor.getNavigableRole(), collection.getKey() )
			);

			// EARLY EXIT!!
			return;
		}

		int passes = 0;
		int count = 0;
		collection.preInsert( collectionDescriptor );

		while ( entries.hasNext() ) {
			final Object entry = entries.next();
			if ( collection.entryExists( entry, passes ) ) {
				bindCollectionKey( key, jdbcParameterBindings, session );
				bindCollectionId( entry, passes, collection, jdbcParameterBindings, session );
				bindCollectionIndex( entry, passes, collection, jdbcParameterBindings, session );
				bindCollectionElement( entry, collection, jdbcParameterBindings, session );

				count++;
				JdbcMutationExecutor.WITH_AFTER_STATEMENT_CALL.execute( creationOperation, executionContext );
			}
			passes++;
			jdbcParameterBindings.clear();
		}

		log.debugf( "Done inserting rows: %s inserted", count );
	}

	@SuppressWarnings("WeakerAccess")
	protected void bindCollectionId(
			Object entry,
			int assumedIdentifier,
			PersistentCollection collection,
			JdbcParameterBindingsImpl jdbcParameterBindings,
			SharedSessionContractImplementor session) {
		// todo (6.0) : probably not the correct `assumedIdentifier`
		if ( collectionDescriptor.getIdDescriptor() != null ) {
			final Object identifier = collection.getIdentifier( entry, assumedIdentifier, collectionDescriptor );
			collectionDescriptor.getIdDescriptor().dehydrate(
					collectionDescriptor.getIdDescriptor().unresolve( identifier, session ),
					(jdbcValue, type, boundColumn) -> createBinding(
							jdbcValue,
							boundColumn,
							type,
							jdbcParameterBindings,
							session
					),
					Clause.INSERT,
					session
			);
		}
	}

	@SuppressWarnings("WeakerAccess")
	protected void bindCollectionIndex(
			Object entry,
			int assumedIndex,
			PersistentCollection collection,
			JdbcParameterBindingsImpl jdbcParameterBindings,
			SharedSessionContractImplementor session) {
		final CollectionIndex indexDescriptor = collectionDescriptor.getIndexDescriptor();
		if ( indexDescriptor != null ) {
			Object index = collection.getIndex( entry, assumedIndex, collectionDescriptor );
			if ( indexDescriptor.getBaseIndex() != 0 ) {
				index = (Integer) index + indexDescriptor.getBaseIndex();
			}
			indexDescriptor.dehydrate(
					indexDescriptor.unresolve( index, session ),
					(jdbcValue, type, boundColumn) -> createBinding(
							jdbcValue,
							boundColumn,
							type,
							jdbcParameterBindings,
							session
					),
					Clause.INSERT,
					session
			);
		}
	}

	@SuppressWarnings("WeakerAccess")
	protected void bindCollectionKey(
			Object key,
			JdbcParameterBindingsImpl jdbcParameterBindings,
			SharedSessionContractImplementor session) {
		collectionDescriptor.getCollectionKeyDescriptor().dehydrate(
				collectionDescriptor.getCollectionKeyDescriptor().unresolve( key, session ),
				(jdbcValue, type, boundColumn) -> createBinding(
						jdbcValue,
						boundColumn,
						type,
						jdbcParameterBindings,
						session
				),
				Clause.INSERT,
				session
		);
	}

	@SuppressWarnings("WeakerAccess")
	protected void createBinding(
			Object jdbcValue,
			Column boundColumn,
			SqlExpressableType type,
			JdbcParameterBindingsImpl jdbcParameterBindings,
			SharedSessionContractImplementor session) {
		final JdbcParameter jdbcParameter = resolveJdbcParameter( boundColumn );

		jdbcParameterBindings.addBinding(
				jdbcParameter,
				new LiteralParameter(
						jdbcValue,
						type,
						Clause.INSERT,
						session.getFactory().getTypeConfiguration()
				)
		);
	}

	protected JdbcParameter resolveJdbcParameter(Column boundColumn) {
		final JdbcParameter jdbcParameter = jdbcParameterMap.get( boundColumn );
		if ( jdbcParameter == null ) {
			throw new IllegalStateException( "JdbcParameter not found for Column [" + boundColumn + "]" );
		}
		return jdbcParameter;
	}

	protected void bindCollectionElement(
			Object entry,
			PersistentCollection collection,
			JdbcParameterBindingsImpl jdbcParameterBindings,
			SharedSessionContractImplementor session) {
		collectionDescriptor.getElementDescriptor().dehydrate(
				collectionDescriptor.getElementDescriptor().unresolve(
						collection.getElement( entry, collectionDescriptor ),
						session
				),
				(jdbcValue, type, boundColumn) -> createBinding(
						jdbcValue,
						boundColumn,
						type,
						jdbcParameterBindings,
						session
				),
				Clause.INSERT,
				session
		);
	}
}
