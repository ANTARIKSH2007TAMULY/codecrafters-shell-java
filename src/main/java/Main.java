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

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        int nextJobId = 1;
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
                if (findExecutable(command) != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
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
                    Process process = pb.start();
                    if (background) {
                        System.out.println("[" + nextJobId + "] " + process.pid());
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
