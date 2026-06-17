import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static class StdoutRedirect {
        final String file;
        final boolean append;

        StdoutRedirect(String file, boolean append) {
            this.file = file;
            this.append = append;
        }
    }

    private static class StderrRedirect {
        final String file;
        final boolean append;

        StderrRedirect(String file, boolean append) {
            this.file = file;
            this.append = append;
        }
    }

    private static class Job {
        final int id;
        final long pid;
        final String command;
        final Process process;

        Job(int id, long pid, String command, Process process) {
            this.id = id;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }

    private static int smallestAvailableJobId(List<Job> jobs) {
        int candidate = 1;
        while (true) {
            boolean used = false;
            for (Job job : jobs) {
                if (job.id == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return candidate;
            }
            candidate++;
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        int nextJobId = 1;
        List<Job> jobs = new ArrayList<>();
        List<String> history = new ArrayList<>();
        while (true) {
            reapCompletedJobs(jobs);
            nextJobId = smallestAvailableJobId(jobs);
            System.out.print("$ ");
            String input = scanner.nextLine();
            List<String> parts = parseInput(input);
            if (parts.isEmpty()) {
                continue;
            }
            history.add(input);
            StdoutRedirect outputRedirect = extractStdoutRedirect(parts);
            StderrRedirect errorRedirect = extractStderrRedirect(parts);
            boolean background = false;
            if (!parts.isEmpty() && parts.get(parts.size() - 1).equals("&")) {
                parts.remove(parts.size() - 1);
                background = true;
            }
            if (parts.isEmpty()) {
                continue;
            }
            String command = parts.get(0);
            List<List<String>> pipeline = splitPipeline(parts);
            if (command.equals("exit")) {
                break;
            }
            if (pipeline.size() >= 2) {
                nextJobId = executePipeline(pipeline, outputRedirect, errorRedirect, background, jobs, input, nextJobId);
            } else if (command.equals("echo")) {
                String output = String.join(" ", parts.subList(1, parts.size()));
                if (errorRedirect != null && !errorRedirect.append) {
                    Files.writeString(Paths.get(errorRedirect.file), "");
                }
                if (outputRedirect != null) {
                    writeStdout(outputRedirect, output + "\n");
                } else {
                    System.out.println(output);
                }
            } else if (command.equals("pwd")) {
                String dir = System.getProperty("user.dir");
                if (errorRedirect != null && !errorRedirect.append) {
                    Files.writeString(Paths.get(errorRedirect.file), "");
                }
                if (outputRedirect != null) {
                    writeStdout(outputRedirect, dir + "\n");
                } else {
                    System.out.println(dir);
                }
            } else if (command.equals("cd")) {
                String dir = parts.get(1);
                if (dir.startsWith("~")) {
                    dir = System.getenv("HOME") + dir.substring(1);
                }
                Path path = Paths.get(System.getProperty("user.dir")).resolve(dir).normalize();
                if (Files.isDirectory(path)) {
                    System.setProperty("user.dir", path.toString());
                } else {
                    System.out.println("cd: " + dir + ": No such file or directory");
                }
            } else if (command.equals("jobs")) {
                listJobs(jobs);
            } else if (command.equals("history")) {
                int start = 0;
                if (parts.size() >= 2) {
                    try {
                        int n = Integer.parseInt(parts.get(1));
                        if (n > 0 && n < history.size()) {
                            start = history.size() - n;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                for (int i = start; i < history.size(); i++) {
                    System.out.printf("%5d  %s%n", i + 1, history.get(i));
                }
            } else if (command.equals("type")) {
                String cmd = parts.get(1);
                String output;
                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || cmd.equals("pwd")
                        || cmd.equals("cd") || cmd.equals("jobs") || cmd.equals("history")) {
                    output = cmd + " is a shell builtin";
                } else {
                    String foundPath = findExecutable(cmd);
                    if (foundPath != null) {
                        output = cmd + " is " + foundPath;
                    } else {
                        output = cmd + ": not found";
                    }
                }
                if (outputRedirect != null) {
                    writeStdout(outputRedirect, output + "\n");
                } else {
                    System.out.println(output);
                }
                if (errorRedirect != null && !errorRedirect.append) {
                    Files.writeString(Paths.get(errorRedirect.file), "");
                }
            } else if (findExecutable(command) != null) {
                ProcessBuilder pb = new ProcessBuilder(parts);
                configureRedirects(pb, outputRedirect, errorRedirect);
                Process process = pb.start();
                if (background) {
                    System.out.println("[" + nextJobId + "] " + process.pid());
                    System.out.flush();
                    jobs.add(new Job(nextJobId, process.pid(), input.trim(), process));
                    nextJobId = smallestAvailableJobId(jobs);
                } else {
                    process.waitFor();
                }
            } else {
                System.out.println(command + ": command not found");
            }
        }
    }

    private static String getJobMarker(int jobId, List<Job> jobs) {
        int maxId = -1;
        int secondMaxId = -1;
        for (Job job : jobs) {
            if (job.id > maxId) {
                secondMaxId = maxId;
                maxId = job.id;
            } else if (job.id > secondMaxId) {
                secondMaxId = job.id;
            }
        }
        if (jobId == maxId) {
            return "+";
        } else if (jobId == secondMaxId) {
            return "-";
        }
        return " ";
    }

    private static String displayCommandForDone(Job job) {
        String displayCommand = job.command;
        if (displayCommand.endsWith(" &")) {
            displayCommand = displayCommand.substring(0, displayCommand.length() - 2);
        }
        return displayCommand;
    }

    private static void reapCompletedJobs(List<Job> jobs) throws Exception {
        List<Job> remaining = new ArrayList<>();
        for (Job job : jobs) {
            if (!job.process.isAlive()) {
                System.out.printf("[%d]%s  %-24s%s%n", job.id, getJobMarker(job.id, jobs), "Done", displayCommandForDone(job));
                job.process.waitFor();
            } else {
                remaining.add(job);
            }
        }
        jobs.clear();
        jobs.addAll(remaining);
    }

    private static void listJobs(List<Job> jobs) throws Exception {
        List<Job> remaining = new ArrayList<>();
        for (Job job : jobs) {
            boolean running = job.process.isAlive();
            if (running) {
                System.out.printf("[%d]%s  %-24s%s%n", job.id, getJobMarker(job.id, jobs), "Running", job.command);
                remaining.add(job);
            } else {
                System.out.printf("[%d]%s  %-24s%s%n", job.id, getJobMarker(job.id, jobs), "Done", displayCommandForDone(job));
                job.process.waitFor();
            }
        }
        jobs.clear();
        jobs.addAll(remaining);
    }

    private static List<String> parseInput(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingleQuotes) {
                if (c == '\'') {
                    inSingleQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (inDoubleQuotes) {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);
                        if (next == '"' || next == '\\' || next == '$' || next == '`' || next == '\n') {
                            i++;
                            if (next != '\n') {
                                current.append(next);
                            }
                        } else {
                            current.append(c);
                            current.append(next);
                            i++;
                        }
                    } else {
                        current.append(c);
                    }
                } else if (c == '"') {
                    inDoubleQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '\\') {
                if (i + 1 < input.length()) {
                    i++;
                    current.append(input.charAt(i));
                }
            } else if (c == '\'') {
                inSingleQuotes = true;
            } else if (c == '"') {
                inDoubleQuotes = true;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }

    private static boolean isBuiltin(String cmd) {
        return cmd.equals("echo") || cmd.equals("type") || cmd.equals("pwd") || cmd.equals("jobs")
                || cmd.equals("history");
    }

    private static String getBuiltinOutput(List<String> parts) {
        String cmd = parts.get(0);
        if (cmd.equals("echo")) {
            return String.join(" ", parts.subList(1, parts.size()));
        } else if (cmd.equals("pwd")) {
            return System.getProperty("user.dir");
        } else if (cmd.equals("type")) {
            String arg = parts.get(1);
            if (arg.equals("echo") || arg.equals("exit") || arg.equals("type") || arg.equals("pwd")
                    || arg.equals("cd") || arg.equals("jobs") || arg.equals("history")) {
                return arg + " is a shell builtin";
            }
            String foundPath = findExecutable(arg);
            if (foundPath != null) {
                return arg + " is " + foundPath;
            }
            return arg + ": not found";
        }
        return "";
    }

    private static void writeBuiltinOutput(List<String> parts, OutputStream out) throws Exception {
        String output = getBuiltinOutput(parts);
        if (!output.isEmpty()) {
            out.write((output + "\n").getBytes());
            out.flush();
        }
    }

    private static void printBuiltinOutput(List<String> parts, StdoutRedirect outputRedirect) throws Exception {
        String output = getBuiltinOutput(parts);
        if (outputRedirect != null) {
            writeStdout(outputRedirect, output + "\n");
        } else if (!output.isEmpty()) {
            System.out.println(output);
        }
    }

    private static void pipeStream(InputStream in, OutputStream out) {
        try (InputStream input = in; OutputStream output = out) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                output.write(buffer, 0, n);
                output.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static void drainStream(InputStream in) {
        try (InputStream input = in) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) != -1) {
            }
        } catch (Exception ignored) {
        }
    }

    private static int executePipeline(List<List<String>> stages, StdoutRedirect outputRedirect,
            StderrRedirect errorRedirect, boolean background, List<Job> jobs, String input, int nextJobId) throws Exception {
        nextJobId = smallestAvailableJobId(jobs);
        boolean hasBuiltin = false;
        for (List<String> stage : stages) {
            if (isBuiltin(stage.get(0))) {
                hasBuiltin = true;
                break;
            }
        }
        if (hasBuiltin && stages.size() == 2) {
            return executeDualPipeline(stages.get(0), stages.get(1), outputRedirect, errorRedirect, background, jobs, input, nextJobId);
        }
        return executeMultiStagePipeline(stages, outputRedirect, errorRedirect, background, jobs, input, nextJobId);
    }

    private static int executeMultiStagePipeline(List<List<String>> stages, StdoutRedirect outputRedirect,
            StderrRedirect errorRedirect, boolean background, List<Job> jobs, String input, int nextJobId) throws Exception {
        nextJobId = smallestAvailableJobId(jobs);
        int n = stages.size();
        for (List<String> stage : stages) {
            String cmd = stage.get(0);
            if (!isBuiltin(cmd) && findExecutable(cmd) == null) {
                System.out.println(cmd + ": command not found");
                return nextJobId;
            }
        }

        Process[] processes = new Process[n];
        for (int i = 0; i < n; i++) {
            ProcessBuilder pb = new ProcessBuilder(stages.get(i));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            if (i < n - 1) {
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            } else {
                configureOutputAndError(pb, outputRedirect, errorRedirect);
            }
            if (i > 0) {
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            }
            processes[i] = pb.start();
        }

        for (int i = 0; i < n - 1; i++) {
            final int idx = i;
            Thread pipeThread = new Thread(() -> pipeStream(processes[idx].getInputStream(), processes[idx + 1].getOutputStream()));
            pipeThread.setDaemon(true);
            pipeThread.start();
        }

        Process last = processes[n - 1];
        if (background) {
            System.out.println("[" + nextJobId + "] " + last.pid());
            System.out.flush();
            jobs.add(new Job(nextJobId, last.pid(), input.trim(), last));
            return smallestAvailableJobId(jobs);
        }

        last.waitFor();
        for (int i = 0; i < n - 1; i++) {
            if (processes[i].isAlive()) {
                processes[i].destroyForcibly();
            }
            processes[i].waitFor();
        }
        return smallestAvailableJobId(jobs);
    }

    private static int executeDualPipeline(List<String> left, List<String> right, StdoutRedirect outputRedirect,
            StderrRedirect errorRedirect, boolean background, List<Job> jobs, String input, int nextJobId) throws Exception {
        nextJobId = smallestAvailableJobId(jobs);
        String leftCmd = left.get(0);
        String rightCmd = right.get(0);
        boolean leftBuiltin = isBuiltin(leftCmd);
        boolean rightBuiltin = isBuiltin(rightCmd);

        if (!leftBuiltin && findExecutable(leftCmd) == null) {
            System.out.println(leftCmd + ": command not found");
            return nextJobId;
        }
        if (!rightBuiltin && findExecutable(rightCmd) == null) {
            System.out.println(rightCmd + ": command not found");
            return nextJobId;
        }

        if (leftBuiltin && rightBuiltin) {
            PipedInputStream pipeIn = new PipedInputStream();
            PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
            Thread leftThread = new Thread(() -> {
                try (OutputStream out = pipeOut) {
                    writeBuiltinOutput(left, out);
                } catch (Exception ignored) {
                }
            });
            leftThread.start();
            Thread drainThread = new Thread(() -> drainStream(pipeIn));
            drainThread.setDaemon(true);
            drainThread.start();
            printBuiltinOutput(right, outputRedirect);
            leftThread.join();
            drainThread.join();
        } else if (leftBuiltin) {
            PipedInputStream pipeIn = new PipedInputStream();
            PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
            ProcessBuilder pb2 = new ProcessBuilder(right);
            pb2.redirectInput(ProcessBuilder.Redirect.PIPE);
            configureOutputAndError(pb2, outputRedirect, errorRedirect);
            Process p2 = pb2.start();
            Thread pipeThread = new Thread(() -> pipeStream(pipeIn, p2.getOutputStream()));
            pipeThread.setDaemon(true);
            pipeThread.start();
            try (OutputStream out = pipeOut) {
                writeBuiltinOutput(left, out);
            }
            if (background) {
                System.out.println("[" + nextJobId + "] " + p2.pid());
                System.out.flush();
                jobs.add(new Job(nextJobId, p2.pid(), input.trim(), p2));
                return smallestAvailableJobId(jobs);
            }
            p2.waitFor();
            pipeThread.join();
        } else if (rightBuiltin) {
            ProcessBuilder pb1 = new ProcessBuilder(left);
            pb1.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process p1 = pb1.start();
            Thread drainThread = new Thread(() -> drainStream(p1.getInputStream()));
            drainThread.setDaemon(true);
            drainThread.start();
            printBuiltinOutput(right, outputRedirect);
            p1.waitFor();
            drainThread.join();
        } else {
            ProcessBuilder pb1 = new ProcessBuilder(left);
            pb1.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process p1 = pb1.start();
            ProcessBuilder pb2 = new ProcessBuilder(right);
            pb2.redirectInput(ProcessBuilder.Redirect.PIPE);
            configureOutputAndError(pb2, outputRedirect, errorRedirect);
            Process p2 = pb2.start();
            Thread pipeThread = new Thread(() -> pipeStream(p1.getInputStream(), p2.getOutputStream()));
            pipeThread.setDaemon(true);
            pipeThread.start();
            if (background) {
                System.out.println("[" + nextJobId + "] " + p2.pid());
                System.out.flush();
                jobs.add(new Job(nextJobId, p2.pid(), input.trim(), p2));
                return smallestAvailableJobId(jobs);
            }
            p2.waitFor();
            if (p1.isAlive()) {
                p1.destroyForcibly();
            }
            p1.waitFor();
        }
        return smallestAvailableJobId(jobs);
    }

    private static List<List<String>> splitPipeline(List<String> parts) {
        List<List<String>> commands = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String part : parts) {
            if (part.equals("|")) {
                commands.add(current);
                current = new ArrayList<>();
            } else {
                current.add(part);
            }
        }
        commands.add(current);
        return commands;
    }

    private static void configureRedirects(ProcessBuilder pb, StdoutRedirect outputRedirect, StderrRedirect errorRedirect) {
        if (outputRedirect == null && errorRedirect == null) {
            pb.inheritIO();
        } else {
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            configureOutputAndError(pb, outputRedirect, errorRedirect);
        }
    }

    private static void configureOutputAndError(ProcessBuilder pb, StdoutRedirect outputRedirect, StderrRedirect errorRedirect) {
        if (outputRedirect != null) {
            if (outputRedirect.append) {
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(outputRedirect.file)));
            } else {
                pb.redirectOutput(new File(outputRedirect.file));
            }
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }
        if (errorRedirect != null) {
            if (errorRedirect.append) {
                pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(errorRedirect.file)));
            } else {
                pb.redirectError(new File(errorRedirect.file));
            }
        } else {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
    }

    private static StdoutRedirect extractStdoutRedirect(List<String> parts) {
        for (int i = 0; i < parts.size(); i++) {
            String token = parts.get(i);
            if (token.equals(">") || token.equals("1>")) {
                String file = parts.get(i + 1);
                parts.remove(i + 1);
                parts.remove(i);
                return new StdoutRedirect(file, false);
            } else if (token.equals(">>") || token.equals("1>>")) {
                String file = parts.get(i + 1);
                parts.remove(i + 1);
                parts.remove(i);
                return new StdoutRedirect(file, true);
            }
        }
        return null;
    }

    private static void writeStdout(StdoutRedirect redirect, String content) throws Exception {
        if (redirect.append) {
            Files.writeString(Paths.get(redirect.file), content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.writeString(Paths.get(redirect.file), content);
        }
    }

    private static StderrRedirect extractStderrRedirect(List<String> parts) {
        for (int i = 0; i < parts.size(); i++) {
            String token = parts.get(i);
            if (token.equals("2>")) {
                String file = parts.get(i + 1);
                parts.remove(i + 1);
                parts.remove(i);
                return new StderrRedirect(file, false);
            } else if (token.equals("2>>")) {
                String file = parts.get(i + 1);
                parts.remove(i + 1);
                parts.remove(i);
                return new StderrRedirect(file, true);
            }
        }
        return null;
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path filePath = Paths.get(dir, command);
            if (Files.exists(filePath) && Files.isExecutable(filePath)) {
                return filePath.toString();
            }
        }
        return null;
    }
}
