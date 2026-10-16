package smithereen.media.sniffing;

import java.io.File;

import smithereen.media.MediaValidationException;
import smithereen.model.media.MediaKind;

/**
 * One link of the chain that works out what kind of media a file is: it either recognizes the file or hands it to the next link.
 */
public abstract class MediaDecoder {
	private final MediaDecoder next;

	protected MediaDecoder(MediaDecoder next) {
		this.next = next;
	}

	/**
	 * @throws MediaValidationException if no link in the chain recognizes the file
	 */
	public final MediaKind decode(File file) throws MediaValidationException {
		MediaKind kind = tryDecode(file);

		return kind != null ? kind : next.decode(file);
	}

	/**
	 * @return the kind, or null to pass the file on
	 */
	protected abstract MediaKind tryDecode(File file) throws MediaValidationException;
}
