package smithereen.model;

import smithereen.model.objects.StoredObject;

public class PollVote extends StoredObject{
	@Override
	public String getType(){
		return "Note";
	}
}
