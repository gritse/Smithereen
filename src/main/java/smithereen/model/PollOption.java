package smithereen.model;

import java.sql.ResultSet;
import java.sql.SQLException;


public class PollOption{
	public int id;
	public String text;
	public int numVotes;

	public static PollOption fromResultSet(ResultSet res, Poll poll) throws SQLException{
		PollOption opt=new PollOption();
		opt.id=res.getInt("id");
		opt.text=res.getString("text");
		opt.numVotes=res.getInt("num_votes");
		return opt;
	}
}
