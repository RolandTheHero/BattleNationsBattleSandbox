package hero.roland.bnsim.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Reports errors in a dialog. The packaged exe has no console, so an error
 * that is only printed would otherwise go unseen. The dialog gives the error
 * and the chain of causes behind it, with the full stack trace under "Show
 * details" to copy into a bug report.
 */
public final class ErrorDialog {
	/** Set while the uncaught-error dialog is open, so a repeating error can't stack up dialogs. */
	private static final AtomicBoolean uncaughtShowing = new AtomicBoolean();

	private ErrorDialog() {}

	/** Makes any exception no code catches show a dialog instead of vanishing. */
	public static void installUncaughtHandler() {
		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
			if (!uncaughtShowing.compareAndSet(false, true)) {
				e.printStackTrace();
				return;
			}
			show(null, "Unexpected error", "Something went wrong.", e, () -> uncaughtShowing.set(false));
		});
	}

	/**
	 * Shows {@code t} in an error dialog headed by {@code summary}, and prints its
	 * stack trace. Safe to call from any thread; the dialog is shown on the event
	 * dispatch thread.
	 */
	public static void show(Component parent, String title, String summary, Throwable t) {
		show(parent, title, summary, t, null);
	}

	private static void show(Component parent, String title, String summary, Throwable t, Runnable onClose) {
		t.printStackTrace();
		if (GraphicsEnvironment.isHeadless()) {
			if (onClose != null)
				onClose.run();
			return;
		}
		if (SwingUtilities.isEventDispatchThread())
			showNow(parent, title, summary, t, onClose);
		else
			SwingUtilities.invokeLater(() -> showNow(parent, title, summary, t, onClose));
	}

	private static void showNow(Component parent, String title, String summary, Throwable t, Runnable onClose) {
		try {
			JTextArea message = new JTextArea(summary + "\n\n" + describe(t));
			message.setEditable(false);
			message.setOpaque(false);
			message.setFont(UIManager.getFont("Label.font"));

			JTextArea trace = new JTextArea(stackTrace(t));
			trace.setEditable(false);
			trace.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			trace.setCaretPosition(0);
			JScrollPane traceScroll = new JScrollPane(trace);
			traceScroll.setPreferredSize(new Dimension(640, 260));
			traceScroll.setVisible(false);

			JPanel panel = new JPanel(new BorderLayout(0, 8));
			JCheckBox details = new JCheckBox("Show details");
			details.addActionListener(e -> {
				traceScroll.setVisible(details.isSelected());
				Window window = SwingUtilities.getWindowAncestor(panel);
				if (window != null)
					window.pack();
			});
			panel.add(message, BorderLayout.NORTH);
			panel.add(details, BorderLayout.CENTER);
			panel.add(traceScroll, BorderLayout.SOUTH);

			JOptionPane.showMessageDialog(parent, panel, title, JOptionPane.ERROR_MESSAGE);
		} finally {
			if (onClose != null)
				onClose.run();
		}
	}

	/**
	 * The error's message, then a "Caused by" line for each cause. An
	 * {@link IOException}'s message is shown alone, as it is written for the
	 * user; anything else is named by its type too.
	 */
	private static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder(t instanceof IOException && t.getMessage() != null
				? t.getMessage() : name(t));
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		seen.add(t);
		for (Throwable cause = t.getCause(); cause != null && seen.add(cause); cause = cause.getCause())
			sb.append("\nCaused by: ").append(name(cause));
		return sb.toString();
	}

	private static String name(Throwable t) {
		String message = t.getMessage();
		return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
	}

	private static String stackTrace(Throwable t) {
		StringWriter out = new StringWriter();
		t.printStackTrace(new PrintWriter(out));
		return out.toString();
	}
}
