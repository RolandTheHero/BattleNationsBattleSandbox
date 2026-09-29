package hero.roland.bnsim.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.URLStreamHandler;
import java.net.spi.URLStreamHandlerProvider;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import hero.roland.bnsim.gamefiles.GameFiles;

/**
 * Serves the active bundle's images at {@code bnsimimage:<name>} URLs, straight
 * from memory, so Swing's HTML text (which loads {@code <img>} sources by URL)
 * can show them without any files. Registered as a service in
 * {@code META-INF/services/java.net.spi.URLStreamHandlerProvider}.
 */
public class ImageUrls extends URLStreamHandlerProvider {
	private static final String PROTOCOL = "bnsimimage";

	/** A URL for the image with the given name (see {@link GameFiles#getImage}). */
	static String url(String name) {
		return PROTOCOL + ":" + URLEncoder.encode(name, StandardCharsets.UTF_8);
	}

	@Override
	public URLStreamHandler createURLStreamHandler(String protocol) {
		return PROTOCOL.equals(protocol) ? new Handler() : null;
	}

	private static class Handler extends URLStreamHandler {
		@Override
		protected URLConnection openConnection(URL url) {
			return new URLConnection(url) {
				@Override
				public void connect() {
				}

				@Override
				public String getContentType() {
					return "image/png";
				}

				@Override
				public InputStream getInputStream() throws IOException {
					String name = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8);
					BufferedImage image = GameFiles.active().getImage(name);
					if (image == null)
						throw new IOException("No image " + name);
					ByteArrayOutputStream png = new ByteArrayOutputStream();
					ImageIO.write(image, "png", png);
					return new ByteArrayInputStream(png.toByteArray());
				}
			};
		}
	}
}
