package com.piperostool.privileged.server;

import org.json.JSONObject;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Base64;
import android.os.IBinder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Long-lived command endpoint started by Wireless debugging with app_process.
 * It runs as Android's shell UID and speaks a bounded JSON-lines protocol over
 * the authenticated ADB shell stream. No command output is written outside that protocol.
 */
public final class PiperShellServer {
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final long MAX_TIMEOUT_MS = 120_000L;

    private PiperShellServer() { }

    public static void main(String[] args) {
        try (
                BufferedReader input = new BufferedReader(
                        new InputStreamReader(System.in, StandardCharsets.UTF_8));
                BufferedWriter output = new BufferedWriter(
                        new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            write(output, new JSONObject()
                    .put("ready", true)
                    .put("uid", android.os.Process.myUid())
                    .put("pid", android.os.Process.myPid())
                    .put("protocol", 1));
            String line;
            while ((line = input.readLine()) != null) {
                JSONObject request;
                try {
                    request = new JSONObject(line);
                    if ("readFile".equals(request.optString("operation"))) {
                        streamFile(request, output);
                    } else if ("listDirectory".equals(request.optString("operation"))) {
                        streamDirectory(request, output);
                    } else if ("file".equals(request.optString("operation"))) {
                        write(output, fileOperation(request));
                    } else if ("transact".equals(request.optString("operation"))) {
                        write(output, transactSystemService(request));
                    } else {
                        write(output, execute(request));
                    }
                } catch (Throwable error) {
                    write(output, new JSONObject()
                            .put("id", -1)
                            .put("exitCode", 125)
                            .put("output", "PiperOS shell server: " + safeMessage(error)));
                }
            }
        } catch (Throwable ignored) {
            // Closing the ADB shell stream is the requested stop signal.
        }
    }

    private static JSONObject fileOperation(JSONObject request) throws Exception {
        int id = request.optInt("id", -1);
        String action = request.optString("action", "");
        String path = request.optString("path", "");
        try {
            validateFilePath(path, !"stat".equals(action));
            switch (action) {
                case "stat": {
                    JSONObject entry = fileMetadata(path);
                    return new JSONObject().put("id", id).put("success", entry != null)
                            .put("entry", entry == null ? JSONObject.NULL : entry);
                }
                case "mkdir":
                    Os.mkdir(path, 0777);
                    return new JSONObject().put("id", id).put("success", true);
                case "rename": {
                    String destination = request.optString("destination", "");
                    validateFilePath(destination, true);
                    Os.rename(path, destination);
                    return new JSONObject().put("id", id).put("success", true);
                }
                case "delete":
                    deletePath(new File(path), request.optBoolean("recursive"));
                    return new JSONObject().put("id", id).put("success", true);
                case "chmod":
                    Os.chmod(path, request.optInt("mode", 0));
                    return new JSONObject().put("id", id).put("success", true);
                default:
                    throw new IllegalArgumentException("Unsupported file operation");
            }
        } catch (Throwable error) {
            return new JSONObject().put("id", id).put("success", false)
                    .put("errorType", error.getClass().getName()).put("error", safeMessage(error));
        }
    }

    private static void streamDirectory(JSONObject request, BufferedWriter output) throws Exception {
        int id = request.optInt("id", -1);
        String path = request.optString("path", "");
        try {
            validateFilePath(path, false);
            File directory = new File(path);
            if (!directory.isDirectory()) throw new IllegalArgumentException("Not a directory");
            File[] files = directory.listFiles();
            if (files == null) throw new SecurityException("Cannot list directory");
            boolean showHidden = request.optBoolean("showHidden");
            java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File file : files) {
                if (!showHidden && file.getName().startsWith(".")) continue;
                JSONObject entry = fileMetadata(file.getAbsolutePath());
                if (entry != null) write(output, new JSONObject().put("id", id).put("entry", entry));
            }
            write(output, new JSONObject().put("id", id).put("done", true).put("success", true));
        } catch (Throwable error) {
            write(output, new JSONObject().put("id", id).put("done", true).put("success", false)
                    .put("errorType", error.getClass().getName()).put("error", safeMessage(error)));
        }
    }

    private static JSONObject fileMetadata(String path) throws Exception {
        StructStat stat = Os.lstat(path);
        boolean directory = OsConstants.S_ISDIR(stat.st_mode);
        boolean symlink = OsConstants.S_ISLNK(stat.st_mode);
        String name = new File(path).getName();
        if (name.isEmpty()) name = "/";
        JSONObject entry = new JSONObject()
                .put("name", name)
                .put("path", path)
                .put("directory", directory)
                .put("size", stat.st_size)
                .put("modified", stat.st_mtime * 1000L)
                .put("mode", modeString(stat.st_mode))
                .put("uid", stat.st_uid)
                .put("gid", stat.st_gid)
                .put("hidden", name.startsWith("."));
        if (symlink) entry.put("link", Os.readlink(path));
        return entry;
    }

    private static String modeString(int mode) {
        StringBuilder result = new StringBuilder();
        result.append(OsConstants.S_ISDIR(mode) ? 'd' : OsConstants.S_ISLNK(mode) ? 'l' : '-');
        int[] bits = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};
        char[] letters = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        for (int i = 0; i < bits.length; i++) result.append((mode & bits[i]) != 0 ? letters[i] : '-');
        return result.toString();
    }

    private static void validateFilePath(String path, boolean mutation) {
        if (path.isEmpty() || !path.startsWith("/") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid absolute path");
        }
        String[] protectedRoots = {"/system", "/system_ext", "/vendor", "/product", "/odm", "/apex",
                "/metadata", "/data/system", "/data/misc"};
        if (mutation && ("/".equals(path) || java.util.Arrays.stream(protectedRoots)
                .anyMatch(root -> path.equals(root) || path.startsWith(root + "/")))) {
            throw new SecurityException("PiperOS shell server refuses protected system paths");
        }
    }

    private static void deletePath(File file, boolean recursive) throws Exception {
        StructStat stat = Os.lstat(file.getAbsolutePath());
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            File[] children = file.listFiles();
            if (children == null) throw new SecurityException("Cannot list directory for deletion");
            if (children.length > 0 && !recursive) throw new IllegalArgumentException("Directory is not empty");
            for (File child : children) deletePath(child, true);
            Os.remove(file.getAbsolutePath());
        } else {
            Os.remove(file.getAbsolutePath());
        }
    }

    private static JSONObject transactSystemService(JSONObject request) throws Exception {
        int id = request.optInt("id", -1);
        String serviceName = request.optString("service", "");
        int code = request.optInt("transactionCode", -1);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (!serviceName.matches("[A-Za-z0-9_.-]{1,100}") || code < 1) {
                throw new IllegalArgumentException("Invalid system service transaction");
            }
            Class<?> managerClass = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = managerClass.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            IBinder binder = (IBinder) getService.invoke(null, serviceName);
            if (binder == null) throw new IllegalStateException("System service is not available: " + serviceName);
            byte[] payload = Base64.decode(request.optString("data", ""), Base64.NO_WRAP);
            data.unmarshall(payload, 0, payload.length);
            data.setDataPosition(0);
            boolean handled = binder.transact(code, data, reply, request.optInt("flags", 0));
            return new JSONObject().put("id", id).put("handled", handled)
                    .put("reply", Base64.encodeToString(reply.marshall(), Base64.NO_WRAP));
        } catch (Throwable error) {
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null
                    ? error.getCause() : error;
            return new JSONObject().put("id", id).put("handled", false)
                    .put("errorType", cause.getClass().getName()).put("error", safeMessage(cause));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void streamFile(JSONObject request, BufferedWriter output) throws Exception {
        int id = request.optInt("id", -1);
        String path = request.optString("path", "");
        if (path.isEmpty() || !path.startsWith("/") || path.indexOf('\0') >= 0) {
            write(output, new JSONObject().put("id", id).put("done", true)
                    .put("exitCode", 2).put("output", "Invalid absolute path"));
            return;
        }
        byte[] buffer = new byte[36 * 1024];
        try (FileInputStream input = new FileInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                write(output, new JSONObject().put("id", id)
                        .put("chunk", Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP)));
            }
            write(output, new JSONObject().put("id", id).put("done", true).put("exitCode", 0));
        } catch (Throwable error) {
            write(output, new JSONObject().put("id", id).put("done", true)
                    .put("exitCode", 13).put("output", safeMessage(error)));
        }
    }

    private static JSONObject execute(JSONObject request) throws Exception {
        int id = request.optInt("id", -1);
        String command = request.optString("command", "");
        if (command.isEmpty()) {
            return new JSONObject().put("id", id).put("exitCode", 125).put("output", "Empty command");
        }
        long timeout = Math.max(1_000L, Math.min(MAX_TIMEOUT_MS, request.optLong("timeoutMs", 30_000L)));
        java.lang.Process child = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true);
            String cwd = request.optString("workingDirectory", "");
            if (!cwd.isEmpty()) builder.directory(new File(cwd));
            JSONObject environment = request.optJSONObject("environment");
            if (environment != null) {
                Iterator<String> keys = environment.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        builder.environment().put(key, environment.optString(key, ""));
                    }
                }
            }
            child = builder.start();
            byte[] stdin = request.optString("stdin", "").getBytes(StandardCharsets.UTF_8);
            if (stdin.length > 1024 * 1024) throw new IllegalArgumentException("Process stdin exceeds 1 MiB");
            child.getOutputStream().write(stdin);
            child.getOutputStream().close();
            java.lang.Process running = child;
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (InputStreamReader stream = new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[8192];
                    int count;
                    int bytes = 0;
                    while ((count = stream.read(buffer)) >= 0) {
                        if (bytes < MAX_OUTPUT_BYTES) {
                            int retained = Math.min(count, MAX_OUTPUT_BYTES - bytes);
                            output.append(buffer, 0, retained);
                            bytes += retained;
                        }
                    }
                } catch (Throwable ignored) { }
            }, "PiperOS-shell-output");
            reader.setDaemon(true);
            reader.start();
            boolean completed = child.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!completed) {
                child.destroy();
                if (!child.waitFor(500, TimeUnit.MILLISECONDS)) child.destroyForcibly();
            }
            reader.join(1_000L);
            String result = output.toString();
            if (output.length() > MAX_OUTPUT_BYTES) result = result.substring(0, MAX_OUTPUT_BYTES);
            if (!completed) result += "\n[PiperOS: command timed out after " + timeout + " ms]";
            int exitCode = completed ? child.exitValue() : 124;
            return new JSONObject().put("id", id).put("exitCode", exitCode).put("output", result);
        } catch (Throwable error) {
            if (child != null) child.destroyForcibly();
            return new JSONObject().put("id", id).put("exitCode", 125)
                    .put("errorType", error.getClass().getName())
                    .put("output", "PiperOS shell server: " + safeMessage(error));
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static void write(BufferedWriter output, JSONObject value) throws Exception {
        output.write(value.toString());
        output.newLine();
        output.flush();
    }
}
