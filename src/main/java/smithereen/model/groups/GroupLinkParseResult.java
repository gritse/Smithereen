package smithereen.model.groups;

import smithereen.model.media.MediaFileRef;
import smithereen.controllers.ObjectLinkResolver;

public record GroupLinkParseResult(ObjectLinkResolver.ObjectTypeAndID apObject, String title, String imageURL, MediaFileRef image){
}
