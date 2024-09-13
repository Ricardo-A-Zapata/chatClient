package practiceChatServer;

import java.io.*;
import java.util.*;
import java.net.*;
import java.sql.*;
import java.util.concurrent.*;
import javax.swing.*;
import java.awt.*;
import java.sql.DriverManager;
import java.awt.event.ActionEvent;

/**
 *
 * @author ricardozapata
 */
public class practiceChatServer {

    private static Map<String, PrintStream> clients = new ConcurrentHashMap<>();

    static class ClientProcessor implements Runnable {

        Socket client;
        String userName;

        public ClientProcessor(Socket newClient) {
            client = newClient;
        }

        @Override
        public void run() {
            try (PrintStream sout = new PrintStream(client.getOutputStream());
                    Scanner sin = new Scanner(client.getInputStream())) {
                String credentials = sin.nextLine();
                if (authenticate(credentials)) {
                    userName = credentials.split(":")[0];
                    sout.println("200");
                    logConnection(userName, client.getInetAddress().toString());
                    clients.put(userName, sout);

                    String inputLine;
                    while (sin.hasNextLine() && (inputLine = sin.nextLine()) != null) {
                        for (PrintStream writer : new ArrayList<>(clients.values())) {
                            writer.println(userName + ": " + inputLine); // Broadcasting the username with the message
                        }
                    }
                } else {
                    sout.println("500");
                }
            } catch (IOException | NoSuchElementException ex) {
                System.out.println("Error Reached: error handling client " + userName + ": " + ex.getMessage());
            } finally {
                if (userName != null) {
                    clients.remove(userName);
                }
                try {
                    client.close();
                } catch (IOException ex) {
                    System.out.println("Client socket could not close: " + ex.getMessage());
                }
            }
        }

        private boolean authenticate(String credentials) {
            String[] parts = credentials.split(":");
            String userName = parts[0];
            String passWord = parts[1];

            try (Connection conn = DriverManager.getConnection("jdbc:mysql://localhost:8080/chatapp", "root", "");
                    PreparedStatement stmt = conn.prepareStatement("SELECT * FROM users WHERE username = ? AND password = ?")) {
                stmt.setString(1, userName);
                stmt.setString(2, passWord);

                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException ex) {
                System.out.println("Database connection error: " + ex.getMessage());
                return false;
            }
        }

        private void logConnection(String userName, String ip) {
            try (Connection conn = DriverManager.getConnection("jdbc:mysql://localhost:8080/chatapp", "root", "");
                    PreparedStatement stmt = conn.prepareStatement("INSERT INTO LOGINS (userName, ipAddress, loginTime) VALUES (?, ?, NOW())")) {
                stmt.setString(1, userName);
                stmt.setString(2, ip);
                stmt.executeUpdate();
            } catch (SQLException ex) {
                System.out.println("Failed to log connection");
            }
        }
    }

    static class ChatClient extends JFrame {

        private JTextField userInput;
        private JTextArea chatArea;
        private PrintStream sout;
        private Socket socket;
        private String userName;  // To hold the user's name

        public ChatClient() {
            super("Chat Client");
            createGUI();
            startConnection();
        }

        private void createGUI() {
            userInput = new JTextField(50);
            chatArea = new JTextArea(16, 50);
            chatArea.setEditable(false);

            JButton sendButton = new JButton("Send");
            sendButton.addActionListener(this::sendMessage);

            userInput.addActionListener(this::sendMessage);

            JPanel panel = new JPanel(new BorderLayout());
            panel.add(userInput, BorderLayout.CENTER);
            panel.add(sendButton, BorderLayout.EAST);

            add(new JScrollPane(chatArea), BorderLayout.CENTER);
            add(panel, BorderLayout.SOUTH);

            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(600, 400);
            setVisible(true);
        }

        public void startConnection() {
            String serverAddress = JOptionPane.showInputDialog(
                    this, "Enter the Server IP:", "Welcome to the Chatroom", JOptionPane.QUESTION_MESSAGE);
            String usernameAndPassword = JOptionPane.showInputDialog(
                    this, "Enter username and password (format: username:password):");

            try {
                socket = new Socket(serverAddress, 5190);
                sout = new PrintStream(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                sout.println(usernameAndPassword);
                userName = usernameAndPassword.split(":")[0];  // Extract the username

                String response = in.readLine();
                if (!"200".equals(response)) {
                    JOptionPane.showMessageDialog(this, "Authentication failed.");
                    System.exit(0);
                }

                // Now read incoming messages and display them in the chat area
                while (true) {
                    String line = in.readLine();
                    if (line != null) {
                        chatArea.append(line + "\n"); // Directly append incoming messages to chatArea
                        chatArea.setCaretPosition(chatArea.getDocument().getLength()); // Scroll to the bottom
                    } else {
                        break;
                    }
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Failed to connect to server: " + e.getMessage(),
                        "Connection Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void sendMessage(ActionEvent event) {
            if (socket.isClosed() || !socket.isConnected()) {
                JOptionPane.showMessageDialog(this, "Connection to server lost.", "Connection Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            String message = userInput.getText();
            if (!message.isEmpty()) {
                try {
                    // Send the message to the server
                    sout.println(userName + ": " + message);  // Send username with message
                    sout.flush();
                    
                    // Display the message in the local chat area
                    chatArea.append(userName + ": " + message + "\n"); // Append the message to chatArea
                    chatArea.setCaretPosition(chatArea.getDocument().getLength()); // Scroll to the bottom

                    // Clear the input field after sending
                    userInput.setText("");
                    System.out.println("Message sent: " + message);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "Failed to send message: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }

    }

    public static void main(String[] args) {
        try {
            ServerSocket ss = new ServerSocket(5190);
            ChatClient chatClient = new ChatClient();

            while (true) {
                Socket client = ss.accept();
                System.out.println("Got connection from: " + client.getInetAddress().toString());
                ClientProcessor cp = new ClientProcessor(client);
                new Thread(cp).start();
            }
        } catch (IOException ex) {
            System.out.println("IO Failure, check for other listeners on port 5190");
            System.exit(-1);
        }
    }
}
