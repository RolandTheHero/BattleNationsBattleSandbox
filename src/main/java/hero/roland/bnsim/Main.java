package hero.roland.bnsim;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.ui.BattleFrame;

public class Main {

    private static final String WELCOME_TEXT =
        "<html><div style='text-align: center; width: 320px;'>"
        + "Welcome to a Battle Nations Battle Sandbox by RolandTheHero.<br><br>"
        + "Select the folder where the game files are located to get started."
        + "</div></html>";

    public static void main(String[] args) {
        // Swing components must be created and shown on the Event Dispatch Thread.
        SwingUtilities.invokeLater(Main::start);
    }

    private static void start() {
        JFrame frame = new JFrame("Battle Nations Battle Sandbox");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JLabel message = new JLabel(WELCOME_TEXT, SwingConstants.CENTER);
        message.setBorder(BorderFactory.createEmptyBorder(20, 20, 10, 20));
        frame.add(message, BorderLayout.NORTH);

        JButton selectButton = new JButton("Select Folder...");
        selectButton.addActionListener(e -> onSelectFolder(frame));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.add(selectButton);
        frame.add(buttonPanel, BorderLayout.CENTER);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void onSelectFolder(JFrame welcome) {
        File folder = chooseFolder();
        if (folder == null) { return; }
        try {
            GameFiles.load(folder);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(welcome,
                    "Could not load game files from:\n" + folder
                            + "\n\n" + e.getMessage(),
                    "Load failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        welcome.dispose();
        new BattleFrame().setVisible(true);
    }

    private static File chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select the bundle folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        // Hide the "All files" filter so only folders are relevant.
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setCurrentDirectory(new File(System.getProperty("user.dir")));

        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }
}
