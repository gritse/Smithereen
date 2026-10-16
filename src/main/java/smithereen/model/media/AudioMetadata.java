package smithereen.model.media;

import org.jetbrains.annotations.Nullable;

/**
 * @param duration in seconds
 * @param title from the file's tags, null if it has none
 * @param artist from the file's tags, null if it has none
 */
public record AudioMetadata(int duration, @Nullable String title, @Nullable String artist) implements MediaFileMetadata{
	@Override
	public int width(){
		return 0;
	}

	@Override
	public int height(){
		return 0;
	}

	@Override
	public String blurhash(){
		return null;
	}

	@Override
	public float[] cropRegion(){
		return null;
	}
}
