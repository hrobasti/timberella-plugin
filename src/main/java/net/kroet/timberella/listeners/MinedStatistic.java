package net.kroet.timberella.listeners;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

/**
 * Counts a felled block in the player's mined statistic the way vanilla does.
 * Paper's incrementStatistic writes the new lifetime total into every objective
 * on that statistic, where vanilla only adds to their scores.
 */
final class MinedStatistic {

    private MinedStatistic() {
    }

    static void award(Player player, Material type) {
        Runnable award = () -> player.incrementStatistic(Statistic.MINE_BLOCK, type);
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            award.run();
            return;
        }
        Criteria criteria = Criteria.statistic(Statistic.MINE_BLOCK, type);
        Scoreboard main = manager.getMainScoreboard();
        Scoreboard own = player.getScoreboard();
        List<Objective> objectives = new ArrayList<>(main.getObjectivesByCriteria(criteria));
        if (own != main)
            objectives.addAll(own.getObjectivesByCriteria(criteria));
        addKeepingScores(objectives, player.getName(), award, 1);
    }

    // Every score the award overwrote gets its own value plus the amount instead;
    // objectives the server doesn't update for statistics stay as they are.
    static void addKeepingScores(List<Objective> objectives, String entry, Runnable award, int amount) {
        List<Snapshot> before = new ArrayList<>(objectives.size());
        for (Objective objective : objectives) {
            Score score = objective.getScore(entry);
            boolean set = score.isScoreSet();
            before.add(new Snapshot(score, set, set ? score.getScore() : 0));
        }
        award.run();
        for (Snapshot snapshot : before) {
            Score score = snapshot.score();
            if (score.isScoreSet() != snapshot.set() || score.getScore() != snapshot.value())
                score.setScore(snapshot.value() + amount);
        }
    }

    private record Snapshot(Score score, boolean set, int value) {
    }
}
