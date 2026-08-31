package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict CSV parser shared by the import preview and execution paths. */
final class CsvParser {

    static final String DEFAULT_ENCODING = "UTF-8";
    private static final Set<String> SUPPORTED_ENCODINGS = Set.of("UTF-8", "GB18030", "ISO-8859-1");
    private static final Set<String> SUPPORTED_DELIMITERS = Set.of(",", ";", "\t", "|");
    private final Charset charset;
    private final char delimiter;
    private final char quote;
    private final boolean hasHeader;
    private final boolean emptyAsNull;

    CsvParser(String encoding, String delimiter, String quote, String escape, boolean hasHeader, boolean emptyAsNull) {
        try {
            charset = Charset.forName(encoding == null ? DEFAULT_ENCODING : encoding);
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{encoding}, e);
        }
        if (!SUPPORTED_ENCODINGS.contains(charset.name())
                || !SUPPORTED_DELIMITERS.contains(delimiter)
                || quote == null || quote.length() != 1
                || escape == null || escape.length() != 1
                || quote.charAt(0) != '"'
                || quote.charAt(0) != escape.charAt(0)
                || delimiter.charAt(0) == quote.charAt(0)) {
            throw new BusinessException("import.preview.invalidCsvOptions");
        }
        this.delimiter = delimiter.charAt(0);
        this.quote = quote.charAt(0);
        this.hasHeader = hasHeader;
        this.emptyAsNull = emptyAsNull;
    }

    CsvResult parse(byte[] bytes, int limit) {
        return parse(new OneByteInputStream(bytes), limit);
    }

    CsvResult parse(InputStream inputStream, int limit) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return parse(new InputStreamReader(new OneByteInputStream(inputStream), decoder), limit);
    }

    CsvResult parse(Reader reader, int limit) {
        try {
            return parseRows(reader, limit);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidEncoding", new Object[]{charset.name()}, e);
        }
    }

    private CsvResult parseRows(Reader reader, int limit) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        PushbackReader pushbackReader = new PushbackReader(reader, 1);
        boolean inQuotedField = false;
        boolean justClosedQuote = false;
        boolean atStartOfField = true;
        int line = 1;
        int quoteStartLine = 1;
        if (limit <= 0) {
            return new CsvResult(rows, hasHeader ? 1 : 0);
        }
        try {
            int next;
            while ((next = pushbackReader.read()) != -1) {
                char current = (char) next;
                if (inQuotedField) {
                    if (current == quote) {
                        int following = pushbackReader.read();
                        if (following == quote) {
                            field.append(quote);
                        } else {
                            if (following != -1) {
                                pushbackReader.unread(following);
                            }
                            inQuotedField = false;
                            justClosedQuote = true;
                        }
                    } else {
                        field.append(current);
                        if (current == '\n') {
                            line++;
                        } else if (current == '\r') {
                            line++;
                            int following = pushbackReader.read();
                            if (following == '\n') {
                                field.append('\n');
                            } else if (following != -1) {
                                pushbackReader.unread(following);
                            }
                        }
                    }
                    continue;
                }
                if (justClosedQuote && current != delimiter && current != '\n' && current != '\r') {
                    throw malformedCsv(line);
                }
                if (current == quote) {
                    if (!atStartOfField) {
                        throw malformedCsv(line);
                    }
                    inQuotedField = true;
                    quoteStartLine = line;
                    atStartOfField = false;
                    continue;
                }
                if (current == delimiter) {
                    fields.add(fieldValue(field));
                    field.setLength(0);
                    justClosedQuote = false;
                    atStartOfField = true;
                    continue;
                }
                if (current == '\n' || current == '\r') {
                    if (current == '\r') {
                        int following = pushbackReader.read();
                        if (following != '\n' && following != -1) {
                            pushbackReader.unread(following);
                        }
                    }
                    fields.add(fieldValue(field));
                    rows.add(row(fields));
                    if (rows.size() >= limit) {
                        return new CsvResult(rows, hasHeader ? 1 : 0);
                    }
                    fields = new ArrayList<>();
                    field.setLength(0);
                    line++;
                    justClosedQuote = false;
                    atStartOfField = true;
                    continue;
                }
                field.append(current);
                atStartOfField = false;
            }
        } catch (CharacterCodingException e) {
            throw new BusinessException("import.preview.invalidEncodingLine", new Object[]{charset.name(), line}, e);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        } catch (Exception e) {
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        }
        if (inQuotedField) {
            throw new BusinessException("import.preview.unclosedQuote", new Object[]{quoteStartLine});
        }
        if (!fields.isEmpty() || field.length() > 0) {
            fields.add(fieldValue(field));
            rows.add(row(fields));
        }
        return new CsvResult(rows, hasHeader ? 1 : 0);
    }

    private String fieldValue(StringBuilder field) {
        if (emptyAsNull && field.length() == 0) {
            return null;
        }
        return field.toString();
    }

    private static BusinessException malformedCsv(int line) {
        return new BusinessException("import.preview.malformedCsv", new Object[]{line});
    }

    private static Map<Integer, String> row(List<String> values) {
        Map<Integer, String> row = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            row.put(index, values.get(index));
        }
        return row;
    }

    record CsvResult(List<Map<Integer, String>> rows, int headerRowCount) {
    }

    private static final class OneByteInputStream extends InputStream {
        private final byte[] bytes;
        private final InputStream inputStream;
        private int position;

        private OneByteInputStream(byte[] bytes) {
            this.bytes = bytes;
            this.inputStream = null;
        }

        private OneByteInputStream(InputStream inputStream) {
            this.bytes = null;
            this.inputStream = inputStream;
        }

        @Override
        public int read() throws IOException {
            if (inputStream != null) {
                return inputStream.read();
            }
            return position >= bytes.length ? -1 : bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (inputStream != null) {
                int next = inputStream.read();
                if (next == -1) {
                    return -1;
                }
                buffer[offset] = (byte) next;
                return 1;
            }
            if (position >= bytes.length) {
                return -1;
            }
            buffer[offset] = bytes[position++];
            return 1;
        }
    }
}
