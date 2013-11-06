package edu.umn.cs.recsys.uu;

import org.grouplens.lenskit.basic.AbstractItemScorer;
import org.grouplens.lenskit.data.dao.ItemEventDAO;
import org.grouplens.lenskit.data.dao.UserEventDAO;
import org.grouplens.lenskit.data.event.Rating;
import org.grouplens.lenskit.data.history.History;
import org.grouplens.lenskit.data.history.RatingVectorUserHistorySummarizer;
import org.grouplens.lenskit.data.history.UserHistory;
import org.grouplens.lenskit.vectors.MutableSparseVector;
import org.grouplens.lenskit.vectors.SparseVector;
import org.grouplens.lenskit.vectors.VectorEntry;
import org.grouplens.lenskit.cursors.Cursor;
import org.grouplens.lenskit.data.event.Event;
import java.util.ArrayList;
import java.util.Collections;

import org.grouplens.lenskit.vectors.similarity.CosineVectorSimilarity;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.management.ImmutableDescriptor;

/**
 * User-user item scorer.
 * @author <a href="http://www.grouplens.org">GroupLens Research</a>
 */
public class SimpleUserUserItemScorer extends AbstractItemScorer {
    private final UserEventDAO userDao;
    private final ItemEventDAO itemDao;

    @Inject
    public SimpleUserUserItemScorer(UserEventDAO udao, ItemEventDAO idao) {
        userDao = udao;
        itemDao = idao;
    }

    @Override
    public void score(long user, @Nonnull MutableSparseVector scores) {
        MutableSparseVector userVector = getUserRatingVector(user).mutableCopy();
        double userMean = userVector.mean();

        // TODO Score items for this user using user-user collaborative filtering
        setMeanRating(userVector);

        Cursor<UserHistory<Event>> users = userDao.streamEventsByUser();
        CosineVectorSimilarity cs = new CosineVectorSimilarity();


        ArrayList<UserRatingSimilarity> userScores = new ArrayList();
        for(UserHistory<Event> h: users.fast()) {
            //Skip the user herself
            long id =  h.getUserId();
            if(id == user) continue;

            UserRatingSimilarity us = new UserRatingSimilarity();
            us.id = id;
            us.ratings = RatingVectorUserHistorySummarizer.makeRatingVector(h).mutableCopy();
            us.mean = us.ratings.mean();
            setMeanRating(us.ratings);

            us.similarity = cs.similarity(userVector, us.ratings);

            userScores.add(us);
        }
        Collections.sort(userScores);

        // This is the loop structure to iterate over items to score
        for (VectorEntry e: scores.fast(VectorEntry.State.EITHER)) {
            long itemId = e.getKey();
            double weightedScore = 0, accumulatedSimilarity = 0;
            int count =0;
            for(UserRatingSimilarity us: userScores){
                if(us.ratings.containsKey(itemId)){
                    accumulatedSimilarity += Math.abs(us.similarity);
                    weightedScore += us.ratings.get(itemId) * us.similarity;
                    count++;

                } else{
                    count = count;
                }

                if(count >=30) {
                    scores.set(itemId, userMean + (weightedScore / accumulatedSimilarity));
                    break;
                }
            }
        }
    }

    /**
     * Get a user's rating vector.
     * @param user The user ID.
     * @return The rating vector.
     */
    private SparseVector getUserRatingVector(long user) {
        UserHistory<Rating> history = userDao.getEventsForUser(user, Rating.class);
        if (history == null) {
            history = History.forUser(user);
        }
        return RatingVectorUserHistorySummarizer.makeRatingVector(history);
    }

    private void setMeanRating(MutableSparseVector userRatings) {
        double meanRating = userRatings.mean();

        for(VectorEntry e: userRatings.fast(VectorEntry.State.EITHER)){
            userRatings.set(e.getKey(), e.getValue() - meanRating);
        }
    }

    public class UserRatingSimilarity implements Comparable <UserRatingSimilarity> {
        public long id;
        public MutableSparseVector ratings;
        public double similarity;
        public double mean;

        public int compareTo(UserRatingSimilarity o){
            double r = o.similarity - similarity;

            if (r > 0) {
                return 1;
            }
            else {
                if (r < 0) return -1;
                else return 0;
            }
        }
    }
}
