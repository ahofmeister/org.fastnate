package org.fastnate.generator.statements;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.fastnate.generator.context.ContextModelListener;
import org.fastnate.generator.context.DefaultContextModelListener;
import org.fastnate.generator.context.GeneratorColumn;
import org.fastnate.generator.context.GeneratorContext;
import org.fastnate.generator.context.GeneratorTable;
import org.fastnate.generator.context.ModelException;
import org.fastnate.generator.dialect.GeneratorDialect;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of a {@link StatementsWriter} that writes bulk text files for each table, and references them in "COPY
 * INTO".
 *
 * @author Tobias Liefke
 */
@Slf4j
public class PostgreSqlBulkWriter extends FileStatementsWriter {

	@RequiredArgsConstructor
	private final class ContextListener extends DefaultContextModelListener {

		@Override
		public void foundColumn(final GeneratorColumn column) {
			try {
				// Close the writer of the new column, as this will change the file structure
				closeBulkWriter(column.getTable());
			} catch (final IOException e) {
				throw new ModelException("Could not close the writer for " + column.getTable(), e);
			}
		}

	}

	/** The current generation context. */
	private final GeneratorContext context;

	/** The current generation context. */
	private final ContextModelListener contextListener = new ContextListener();

	/** The directory for the bulk files. */
	@Getter
	private final File directory;

	/** The encoding of the bulk files. */
	@Getter
	private final Charset encoding;

	/** Remembers for each table which files we've already generated. */
	private final Map<GeneratorTable, Integer> fileNumbers = new HashMap<>();

	/** The open bulk files for each table. */
	private final Map<GeneratorTable, Writer> bulkWriters = new HashMap<>();

	/** All files generated by this writer. */
	@Getter
	private final List<File> generatedFiles = new ArrayList<>();

	/** The count of written statements. */
	@Getter
	private int statementsCount;

	/**
	 * Creates a new instance for a SQL file with UTF-8 encoding.
	 *
	 * All bulk files will end up in the same directory as the given file.
	 *
	 * @param context
	 *            the current generation context
	 * @param sqlFile
	 *            the file that is feeded with all plain statements
	 * @throws FileNotFoundException
	 *             if the directory is not available
	 */
	public PostgreSqlBulkWriter(final GeneratorContext context, final File sqlFile) throws FileNotFoundException {
		this(context, sqlFile, StandardCharsets.UTF_8);
	}

	/**
	 * Creates a new instance for a SQL file.
	 *
	 * All bulk files will end up in the same directory as the given file.
	 *
	 * @param context
	 *            the current generation context
	 * @param sqlFile
	 *            the file that is feeded with all plain statements
	 * @param encoding
	 *            the encoding of all written files
	 * @throws FileNotFoundException
	 *             if the directory is not available
	 */
	@SuppressWarnings("resource")
	public PostgreSqlBulkWriter(final GeneratorContext context, final File sqlFile, final Charset encoding)
			throws FileNotFoundException {
		this(context, sqlFile.getAbsoluteFile().getParentFile(),
				new BufferedWriter(new OutputStreamWriter(new FileOutputStream(sqlFile), encoding)), encoding);
		this.generatedFiles.add(sqlFile);
	}

	/**
	 * Creates a new instance of {@link PostgreSqlBulkWriter}.
	 *
	 * @param context
	 *            the current generation context
	 * @param directory
	 *            the directory for the bulk files
	 * @param writer
	 *            the SQL file which contains the plain and the "COPY" statements
	 * @param encoding
	 *            The encoding of the bulk files.
	 */
	public PostgreSqlBulkWriter(final GeneratorContext context, final File directory, final Writer writer,
			final Charset encoding) {
		super(writer);
		this.context = context;
		this.context.addContextModelListener(this.contextListener);
		this.directory = directory;
		this.encoding = encoding;
	}

	@Override
	public void close() throws IOException {
		this.context.removeContextModelListener(this.contextListener);
		closeBulkWriters();
		getWriter().close();
		log.info("{} statements and {} files written", this.statementsCount, this.generatedFiles.size());
	}

	/**
	 * Closes the current writer of the given table (for example when the table structure has changed).
	 *
	 * @param table
	 *            the table of the writer
	 * @throws IOException
	 *             if there was a problem when closing the writer
	 */
	public void closeBulkWriter(final GeneratorTable table) throws IOException {
		@SuppressWarnings("resource")
		final Writer writer = this.bulkWriters.remove(table);
		if (writer != null) {
			writer.close();
		}
	}

	private void closeBulkWriters() throws IOException {
		for (final Writer bulkWriter : this.bulkWriters.values()) {
			bulkWriter.close();
		}
		this.bulkWriters.clear();
	}

	private Writer findBulkWriter(final GeneratorTable generatorTable, final GeneratorDialect dialect)
			throws IOException {
		Writer bulkWriter = this.bulkWriters.get(generatorTable);
		if (bulkWriter == null) {
			final Integer number = this.fileNumbers.get(generatorTable);
			String fileName = generatorTable.getName();
			if (number == null) {
				this.fileNumbers.put(generatorTable, 2);
			} else {
				fileName += '.' + number.toString();
				this.fileNumbers.put(generatorTable, number + 1);
			}
			final File file = new File(this.directory, fileName + ".blk");
			write("COPY " + generatorTable.getName() + " ("
					+ generatorTable.getColumns().keySet().stream().collect(Collectors.joining(", ")) + ") FROM "
					+ dialect.quoteString(file.getAbsolutePath()) + " WITH ENCODING "
					+ dialect.quoteString(this.encoding.name().toLowerCase()) + getStatementSeparator());
			this.statementsCount++;
			bulkWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), this.encoding));
			this.bulkWriters.put(generatorTable, bulkWriter);
			this.generatedFiles.add(file);
		}
		return bulkWriter;
	}

	@Override
	public void flush() throws IOException {
		for (final Writer bulkWriter : this.bulkWriters.values()) {
			bulkWriter.flush();
		}
		super.flush();
	}

	@Override
	public void writePlainStatement(final GeneratorDialect dialect, final String sql) throws IOException {
		writePlainStatement(sql);
	}

	private void writePlainStatement(final String sql) throws IOException {
		if (!sql.startsWith("TRUNCATE ")) {
			closeBulkWriters();
		}
		write(sql);
		if (!sql.endsWith(getStatementSeparator())) {
			write(getStatementSeparator());
		}
		this.statementsCount++;
	}

	@Override
	@SuppressWarnings("resource")
	public void writeStatement(final EntityStatement stmt) throws IOException {
		if (stmt instanceof InsertStatement) {
			final InsertStatement insert = (InsertStatement) stmt;
			if (!insert.isPlainExpressionAvailable()) {
				// Let's use a bulk file
				final Writer bulkWriter = findBulkWriter(insert.getTable(), insert.getDialect());
				boolean tab = false;
				for (final GeneratorColumn column : insert.getTable().getColumns().values()) {
					if (!column.isAutoGenerated()) {
						if (tab) {
							bulkWriter.write('\t');
						} else {
							tab = true;
						}
						final PrimitiveColumnExpression<?> expression = (PrimitiveColumnExpression<?>) insert
								.getValues().get(column);
						if (expression == null || expression.getValue() == null) {
							bulkWriter.write("\\N");
						} else if (expression.getValue() instanceof String) {
							bulkWriter.write(((String) expression.getValue()).replace("\\", "\\\\").replace("\n", "\\n")
									.replace("\r", "\\r").replace("\t", "\\t"));
						} else {
							bulkWriter.write(expression.getValue().toString());
						}
					}
				}
				bulkWriter.write('\n');
				this.statementsCount++;
				return;
			}
		}
		writePlainStatement(stmt.toSql());
	}
}
