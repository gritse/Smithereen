package smithereen.media;

import java.io.File;

import smithereen.media.sniffing.AudioVideoDecoder;
import smithereen.media.sniffing.ImageDecoder;
import smithereen.media.sniffing.MediaDecoder;
import smithereen.media.sniffing.RejectingDecoder;
import smithereen.model.media.MediaKind;

/**
 * Looks at an uploaded file and says what kind of media it is, from its contents rather than its declared type.
 */
public final class MediaSniffer {
	private MediaSniffer() {}

	/**
	 * @param declaredMimeType the Content-Type the client sent, used only as a hint for which decoder to try first
	 * @throws MediaValidationException if the file is not a kind of media this server accepts
	 */
	public static MediaKind sniff(File file, String declaredMimeType) throws MediaValidationException {
		String declared = declaredMimeType == null ? "" : declaredMimeType;
		MediaDecoder end = new RejectingDecoder(declared);

		// The declared type only decides which decoder goes first; a video named .jpg is still a video
		MediaDecoder chain = declared.startsWith("video/") || declared.startsWith("audio/")
				? new AudioVideoDecoder(new ImageDecoder(end))
				: new ImageDecoder(new AudioVideoDecoder(end));

		return chain.decode(file);
	}
}
