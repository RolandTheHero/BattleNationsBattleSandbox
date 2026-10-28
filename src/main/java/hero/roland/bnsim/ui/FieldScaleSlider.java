package hero.roland.bnsim.ui;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

/**
 * Field-scale slider controlling the zoom of everything drawn on the battlefield —
 * the tiles, units, health bars and other field visuals — scaled about the centre
 * of the field (see {@link BattleField#setFieldScale}). The overlaid UI controls
 * (the weapons box and the battle buttons) are not affected. The slider is
 * logarithmic between {@link #MIN_SCALE} and {@link #MAX_SCALE}, so halving and
 * doubling are the same drag distance; it starts at 1 (no scaling),
 * double-clicking it resets it to 1, and every change is pushed live to the
 * listener. Sits over the arena below the volume slider.
 */
class FieldScaleSlider extends JPanel {

	/** Slider range: zoom factors, mapped logarithmically onto SCALE_STEPS positions. */
	private static final double MIN_SCALE = 0.1;
	private static final double MAX_SCALE = 5.0;
	private static final int SCALE_STEPS = 500;
	private static final int DEFAULT_SCALE_POSITION = scaleToSlider(1.0);

	private final JSlider slider = new JSlider(0, SCALE_STEPS, DEFAULT_SCALE_POSITION);

	FieldScaleSlider(Consumer<Double> listener) {
		super(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		setOpaque(false);
		slider.setOpaque(false);
		slider.setPreferredSize(new Dimension(120, 20));
		slider.setToolTipText(
			"Zoom for the battlefield drawing (tiles, units, bars). Double-click to reset to 1x.");
		slider.addChangeListener(e -> listener.accept(sliderToScale(slider.getValue())));
		slider.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2)
					slider.setValue(DEFAULT_SCALE_POSITION);
			}
		});
		add(new JLabel("Field scale"));
		add(slider);
		listener.accept(sliderToScale(slider.getValue()));
	}

	/**
	 * Maps a slider position (0..{@link #SCALE_STEPS}) onto the zoom factor,
	 * logarithmically from {@link #MIN_SCALE} to {@link #MAX_SCALE}. The default
	 * position maps to exactly 1 so the field's unscaled fast path is kept.
	 */
	private static double sliderToScale(int position) {
		if (position == DEFAULT_SCALE_POSITION)
			return 1.0;
		double t = (double) position / SCALE_STEPS;
		return MIN_SCALE * Math.pow(MAX_SCALE / MIN_SCALE, t);
	}

	/** The slider position closest to the given zoom factor (inverse of {@link #sliderToScale}). */
	private static int scaleToSlider(double scale) {
		double t = Math.log(scale / MIN_SCALE) / Math.log(MAX_SCALE / MIN_SCALE);
		return (int) Math.round(t * SCALE_STEPS);
	}
}
