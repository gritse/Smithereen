package smithereen.model.media;

public record ImageMetadata(int width, int height, String blurhash, float[] cropRegion, boolean animated) implements MediaFileMetadata{
	@Override
	public int duration(){
		return 0;
	}
}
