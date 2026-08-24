package ai.chat2db.community.jcef.handler.biz;


import ai.chat2db.community.jcef.annotation.JcefAction;
import ai.chat2db.community.jcef.builder.ResponseBuilder;
import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.tuple.Pair;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.handler.CefDialogHandler;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Vector;


@JcefAction(value = "select-file", method = "client-command")
public class SelectFileHandler implements IJcefActionHandler {
    private static final String REQUEST_KEY_MULTIPLE = "multiple";
    private static final String REQUEST_KEY_FILE_SIZE = "fileSize";
    private static final String REQUEST_KEY_FILE_TYPE_LIST = "fileTypeList";
    private static final String REQUEST_KEY_STAGE_LOCAL_FILE = "stageLocalFile";
    private static final String RESPONSE_KEY_DATA = "data";
    private static final String RESPONSE_KEY_FILE_PATH = "filePath";
    private static final String RESPONSE_KEY_FILE_NAME = "fileName";
    private static final String DEFAULT_DIALOG_TITLE = "Select File";
    private static final String DEFAULT_FILE_PATH = "";
    private static final String FILE_EXTENSION_PREFIX = ".";
    private static final String EXTENSION_DELIMITER = ",";
    private static final String CEF_EXTENSION_DELIMITER = ";";
    private static final String CEF_FILTER_SEPARATOR = "|";
    private static final String SELECTED_FILES_ACCEPT_FILTER_DESCRIPTION = "Selected Files";
    private static final String DRIVER_FILE_DIRECTORY = "driver-files";

    @Override
    public void handle(ConsoleMessage consoleMessage, ConsoleResult wsResult, CefQueryCallback callback) throws Exception {

        String message = consoleMessage.getMessage();
        JSONObject jsonObject = JSON.parseObject(message);
        Boolean multiple = jsonObject.getBoolean(REQUEST_KEY_MULTIPLE);
        boolean stageLocalFile = jsonObject.getBooleanValue(REQUEST_KEY_STAGE_LOCAL_FILE);
        long maxSizeMB = jsonObject.getLongValue(REQUEST_KEY_FILE_SIZE);
        List<String> fileTypeList = parseFileTypeList(jsonObject.get(REQUEST_KEY_FILE_TYPE_LIST));
        CefBrowser browser = JcefContext.getInstance().getBrowser_();
        if (browser != null
                && openByJcefFileDialog(browser, fileTypeList, Boolean.TRUE.equals(multiple), stageLocalFile, callback)) {
            return;
        }
        openByNativeFileChooser(fileTypeList, maxSizeMB, stageLocalFile, callback);
    }

    private List<String> parseFileTypeList(Object value) {
        if (value == null) {
            return Lists.newArrayList();
        }
        if (value instanceof Iterable<?>) {
            return toFileTypeList((Iterable<?>) value);
        }
        if (value instanceof JSONObject) {
            return parseIndexedFileTypeObject((JSONObject) value);
        }
        if (value instanceof String) {
            return parseFileTypeText((String) value);
        }
        List<String> fileTypes = Lists.newArrayList();
        addFileType(fileTypes, value);
        return fileTypes;
    }

    private List<String> parseFileTypeText(String value) {
        if (value == null || value.isBlank()) {
            return Lists.newArrayList();
        }
        String text = value.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            return toFileTypeList(JSON.parseArray(text));
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            return parseIndexedFileTypeObject(JSON.parseObject(text));
        }
        List<String> fileTypes = Lists.newArrayList();
        for (String fileType : text.split(EXTENSION_DELIMITER)) {
            addFileType(fileTypes, fileType);
        }
        return fileTypes;
    }

    private List<String> parseIndexedFileTypeObject(JSONObject value) {
        List<Map.Entry<String, Object>> entries = Lists.newArrayList(value.entrySet());
        entries.sort(this::compareIndexedEntry);
        List<String> fileTypes = Lists.newArrayList();
        for (Map.Entry<String, Object> entry : entries) {
            addFileType(fileTypes, entry.getValue());
        }
        return fileTypes;
    }

    private List<String> toFileTypeList(Iterable<?> values) {
        List<String> fileTypes = Lists.newArrayList();
        for (Object value : values) {
            addFileType(fileTypes, value);
        }
        return fileTypes;
    }

    private void addFileType(List<String> fileTypes, Object value) {
        if (value == null) {
            return;
        }
        String fileType = String.valueOf(value).trim();
        if (!fileType.isBlank()) {
            fileTypes.add(fileType);
        }
    }

    private int compareIndexedEntry(Map.Entry<String, Object> left, Map.Entry<String, Object> right) {
        int leftIndex = parseIndex(left.getKey());
        int rightIndex = parseIndex(right.getKey());
        if (leftIndex != rightIndex) {
            return Integer.compare(leftIndex, rightIndex);
        }
        return left.getKey().compareTo(right.getKey());
    }

    private int parseIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean openByJcefFileDialog(CefBrowser browser, List<String> fileTypeList, boolean multiple,
                                         boolean stageLocalFile, CefQueryCallback callback) {
        Vector<String> acceptFilters = buildAcceptFilters(fileTypeList);
        CefDialogHandler.FileDialogMode mode = multiple
                ? CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE
                : CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN;
        try {
            browser.runFileDialog(mode, DEFAULT_DIALOG_TITLE, DEFAULT_FILE_PATH, acceptFilters, new CefRunFileDialogCallback() {
                @Override
                public void onFileDialogDismissed(Vector<String> filePaths) {
                    buildFileDialogResponse(filePaths, stageLocalFile, callback);
                }
            });
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Vector<String> buildAcceptFilters(List<String> fileTypeList) {
        Vector<String> acceptFilters = new Vector<>();
        List<String> extensions = Lists.newArrayList();
        fileTypeList.forEach(fileType -> Optional.ofNullable(toAcceptFilter(fileType)).ifPresent(extensions::add));
        if (!extensions.isEmpty()) {
            acceptFilters.add(SELECTED_FILES_ACCEPT_FILTER_DESCRIPTION
                    + CEF_FILTER_SEPARATOR
                    + String.join(CEF_EXTENSION_DELIMITER, extensions));
        }
        return acceptFilters;
    }

    private String toAcceptFilter(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return null;
        }
        return fileType.startsWith(FILE_EXTENSION_PREFIX) ? fileType : FILE_EXTENSION_PREFIX + fileType;
    }

    private void buildFileDialogResponse(Vector<String> filePaths, boolean stageLocalFile, CefQueryCallback callback) {
        if (filePaths == null || filePaths.isEmpty()) {
            ResponseBuilder.buildSuccessJcef(buildResponseData(null), callback);
            return;
        }
        List<Map<@Nullable Object, @Nullable Object>> results = Lists.newArrayList();
        for (String filePath : filePaths) {
            File file = new File(filePath);
            HashMap<@Nullable Object, @Nullable Object> result = Maps.newHashMap();
            result.put(RESPONSE_KEY_FILE_PATH, stageLocalFile ? stageFile(filePath, file.getName()) : filePath);
            result.put(RESPONSE_KEY_FILE_NAME, file.getName());
            results.add(result);
        }
        ResponseBuilder.buildSuccessJcef(buildResponseData(results), callback);
    }

    private void openByNativeFileChooser(List<String> fileTypeList, long maxSizeMB, boolean stageLocalFile,
                                         CefQueryCallback callback) {
        Pair<String, String> pair = OSOperateUtil.openNativeFileChooser(JcefContext.getInstance().getFrame_(),
                null,
                String.join(EXTENSION_DELIMITER, fileTypeList),
                maxSizeMB
        );
        if (pair == null || pair.getLeft() == null) {
            ResponseBuilder.buildSuccessJcef(buildResponseData(null), callback);
            return;
        }
        HashMap<@Nullable Object, @Nullable Object> result = Maps.newHashMap();
        result.put(RESPONSE_KEY_FILE_PATH, stageLocalFile ? stageFile(pair.getLeft(), pair.getRight()) : pair.getLeft());
        result.put(RESPONSE_KEY_FILE_NAME, pair.getRight());
        ResponseBuilder.buildSuccessJcef(buildResponseData(Lists.newArrayList(result)), callback);
    }

    private String stageFile(String filePath, String fileName) {
        try {
            Path source = Path.of(filePath).toAbsolutePath().normalize();
            Path directory = Path.of(ConfigUtils.getBasePath(), DRIVER_FILE_DIRECTORY).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path staged = directory.resolve(UUID.randomUUID() + extension(fileName)).normalize();
            Files.copy(source, staged);
            return staged.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot stage selected file", e);
        }
    }

    private String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf(FILE_EXTENSION_PREFIX);
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    private Map<String, Object> buildResponseData(@Nullable Object data) {
        Map<String, Object> response = Maps.newHashMap();
        response.put(RESPONSE_KEY_DATA, data);
        return response;
    }
}
