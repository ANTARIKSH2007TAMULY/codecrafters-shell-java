import java.io.InputStream;
import java.io.OutputStream;
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

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        int nextJobId = 1;
        List<Job> jobs = new ArrayList<>();
        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine();
            List<String> parts = parseInput(input);
            if (parts.isEmpty()) {
                continue;
            }
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
            if (command.equals("exit")) {
                break;
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
                List<Job> remaining = new ArrayList<>();
                for (int i = 0; i < jobs.size(); i++) {
                    Job job = jobs.get(i);
                    String marker = " ";
                    if (i == jobs.size() - 1) {
                        marker = "+";
                    } else if (i == jobs.size() - 2) {
                        marker = "-";
                    }
                    boolean running = job.process.isAlive();
                    String status = running ? "Running" : "Done";
                    String displayCommand = job.command;
                    if (!running) {
                        if (displayCommand.endsWith(" &")) {
                            displayCommand = displayCommand.substring(0, displayCommand.length() - 2);
                        }
                        job.process.waitFor();
                    } else {
                        remaining.add(job);
                    }
                    System.out.printf("[%d]%s  %-24s%s%n", job.id, marker, status, displayCommand);
                }
                jobs.clear();
                jobs.addAll(remaining);
            } else if (command.equals("type")) {
                String cmd = parts.get(1);
                String output;
                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || cmd.equals("pwd") || cmd.equals("cd") || cmd.equals("jobs")) {
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
            } else {
                List<List<String>> pipeline = splitPipeline(parts);
                if (pipeline.size() == 2) {
                    List<String> left = pipeline.get(0);
                    List<String> right = pipeline.get(1);
                    String leftCmd = left.get(0);
                    String rightCmd = right.get(0);
                    if (findExecutable(leftCmd) == null) {
                        System.out.println(leftCmd + ": command not found");
                    } else if (findExecutable(rightCmd) == null) {
                        System.out.println(rightCmd + ": command not found");
                    } else {
                        ProcessBuilder pb1 = new ProcessBuilder(left);
                        pb1.redirectOutput(ProcessBuilder.Redirect.PIPE);
                        pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
                        Process p1 = pb1.start();

                        ProcessBuilder pb2 = new ProcessBuilder(right);
                        pb2.redirectInput(ProcessBuilder.Redirect.PIPE);
                        configureOutputAndError(pb2, outputRedirect, errorRedirect);
                        Process p2 = pb2.start();

                        Thread pipeThread = new Thread(() -> {
                            try (InputStream in = p1.getInputStream(); OutputStream out = p2.getOutputStream()) {
                                byte[] buffer = new byte[8192];
                                int n;
                                while ((n = in.read(buffer)) != -1) {
                                    out.write(buffer, 0, n);
                                    out.flush();
                                }
                            } catch (Exception ignored) {
                            }
                        });
                        pipeThread.start();

                        if (background) {
                            System.out.println("[" + nextJobId + "] " + p2.pid());
                            System.out.flush();
                            jobs.add(new Job(nextJobId, p2.pid(), input.trim(), p2));
                            nextJobId++;
                        } else {
                            p2.waitFor();
                            if (p1.isAlive()) {
                                p1.destroyForcibly();
                            }
                            pipeThread.join();
                            p1.waitFor();
                        }
                    }
                } else if (findExecutable(command) != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    configureRedirects(pb, outputRedirect, errorRedirect);
                    Process process = pb.start();
                    if (background) {
                        System.out.println("[" + nextJobId + "] " + process.pid());
                        System.out.flush();
                        jobs.add(new Job(nextJobId, process.pid(), input.trim(), process));
                        nextJobId++;
                    } else {
                        process.waitFor();
                    }
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
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
