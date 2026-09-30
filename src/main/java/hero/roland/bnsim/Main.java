package hero.roland.bnsim;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
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
        + "Welcome to Battle Nations Battle Sandbox.<br><br>"
        + "This is an unofficial tool for simulating battles in Battle Nations. It is not affiliated with Madrona Games, and its simulations may not accurately reflect the official game. "
        + "If you publicly share media created with this tool, please make it clear that it was created using this tool and not from official game content.<br><br>"
        + "This is free software under the GNU GPL v2 and comes with ABSOLUTELY NO WARRANTY; see LICENSE and THIRD_PARTY_NOTICES.md.<br><br>"
        + "Choose your game version, then select the folder where its game files are located to get started."
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

        JComboBox<GameFiles.Format> formatBox = new JComboBox<>(GameFiles.Format.values());

        JButton selectButton = new JButton("Select Folder...");
        selectButton.addActionListener(e ->
                onSelectFolder(frame, (GameFiles.Format) formatBox.getSelectedItem()));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        //buttonPanel.add(new JLabel("Game version:"));
        buttonPanel.add(formatBox);
        buttonPanel.add(selectButton);
        frame.add(buttonPanel, BorderLayout.CENTER);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void onSelectFolder(JFrame welcome, GameFiles.Format format) {
        File folder = chooseFolder(format);
        if (folder == null) { return; }
        try {
            GameFiles.load(folder, format);
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

    private static File chooseFolder(GameFiles.Format format) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(format == GameFiles.Format.NEW
                ? "Select the game's install folder" : "Select the bundle folder");
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
