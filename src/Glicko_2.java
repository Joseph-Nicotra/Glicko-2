/*
 *
 * Author:      Joseph Nicotra
 * Version:     1.0
 * Date:        7 December, 2019
 * Description: Implementation of the Glicko-2 rating system in java
 *
 * Source:      [PDF] http://www.glicko.net/glicko/glicko2.pdf
 *
 */

import java.util.*;

public class Glicko_2 {

    static double volatilityConstraint = 0.5;
    static double defaultRating = 1500;
    static double defaultDeviation = 350;
    /**
     * Recommended range: 0.3 - 1.2
     * Smaller values prevent enormous changes in ratings
     */
    static double defaultVolatility = 0.06;

    static HashMap<UUID, Player> players = new HashMap<>();
    static ArrayList<Game> games = new ArrayList<>();

    public static void main(String[] args) {

        // Test values

        UUID player1 = new Player(UUID.randomUUID(), 1500, 200, defaultVolatility).id;
        UUID player2 = new Player(UUID.randomUUID(), 1400, 30, defaultVolatility).id;
        UUID player3 = new Player(UUID.randomUUID(), 1550, 100, defaultVolatility).id;
        UUID player4 = new Player(UUID.randomUUID(), 1700, 300, defaultVolatility).id;

        games.add(new Game(player1, player2, 1));
        games.add(new Game(player1, player3, 2));
        games.add(new Game(player1, player4, 2));

        Player p1 = players.get(player1);

        p1.addRound(games);

        System.out.println(p1.getRange());

    }

    /**
     * Rounds double to n places
     * @param val    Value to round
     * @param places Number of decimal places to round to
     * @return Rounded value
     */
    private static double round(double val, int places) {
        return Math.round(val * Math.pow(10, places)) / Math.pow(10, places);
    }

    /**
     * Player object that stores ratings, histories, and volatilities for all rounds
     */
    static class Player {

        private UUID id;
        /**
         * All values on Glicko scale
         */
        private ArrayList<Double> ratings = new ArrayList<>();
        private ArrayList<Double> deviations = new ArrayList<>();
        private ArrayList<Double> volatilities = new ArrayList<>();

        /**
         * Constructor that uses default values
         *
         * @param id UUID to map player object to
         */
        Player(UUID id) {
            this.id = id;
            ratings.add(defaultRating);
            deviations.add(defaultDeviation);
            volatilities.add(defaultVolatility);
            players.put(id, this);
        }

        /**
         * Constructor that uses custom values
         *
         * @param id         UUID to map player object to
         * @param rating     Player's starting rating
         * @param deviation  Player's starting deviation
         * @param volatility Player's starting volatility
         */
        Player(UUID id, double rating, double deviation, double volatility) {
            this.id = id;
            ratings.add(rating);
            deviations.add(deviation);
            volatilities.add(volatility);
            players.put(id, this);
        }

        /**
         * @return Most recent rating
         */
        double getRating() {
            return ratings.get(ratings.size() - 1);
        }

        /**
         * @return Most recent deviation
         */
        double getDeviation() {
            return deviations.get(deviations.size() - 1);
        }

        /**
         * @return Most recent volatility
         */
        double getVolatility() {
            return volatilities.get(volatilities.size() - 1);
        }

        /**
         * Current rating ± 2 × deviation
         *
         * @return Range of ratings player is in (95% certainty)
         */
        Range getRange() {
            return new Range(getRating(), getRating() - (getDeviation() * 2), getRating() + (getDeviation() * 2));
        }

        /**
         * @param games Games in the current round
         */
        void addRound(Collection<Game> games) {

            // Convert rating & deviation to Glicko-2 scale

            double g2_rating = (getRating() - 1500) / 173.7178;
            double g2_deviation = getDeviation() / 173.7178;

            if (games.size() > 0) {

                // Calculate estimated variance based on game outcomes

                double variance = 0;

                for (Game game : games) {
                    Player opponent = game.getOpponent(id);
                    if (opponent == null) continue;
                    double opponentRating = (opponent.ratings.get(ratings.size() - 1) - 1500) / 173.7178;
                    double opponentDeviation = opponent.deviations.get(deviations.size() - 1) / 173.7178;
                    variance += Math.pow(g(opponentDeviation), 2) * E(g2_rating, opponentRating, opponentDeviation) * (1 - E(g2_rating, opponentRating, opponentDeviation));
                }

                variance = Math.pow(variance, -1);

                // Calculate estimated improvement based on current rating compared to performance rating

                double improvement = 0;

                for (Game game : games) {
                    Player opponent = game.getOpponent(id);
                    if (opponent == null) continue;
                    double opponentRating = (opponent.ratings.get(ratings.size() - 1) - 1500) / 173.7178;
                    double opponentDeviation = opponent.deviations.get(deviations.size() - 1) / 173.7178;
                    improvement += g(opponentDeviation) * (game.getScore(id) - E(g2_rating, opponentRating, opponentDeviation));
                }

                improvement *= variance;

                // Calculate new volatility

                double a = Math.log(Math.pow(getVolatility(), 2));
                double convergenceTolerance = 0.000001;

                double A = Math.log(Math.pow(getVolatility(), 2));
                double B;

                if (Math.pow(improvement, 2) > Math.pow(g2_deviation, 2) + variance) {
                    B = Math.log(Math.pow(improvement, 2) - Math.pow(g2_deviation, 2) - variance);
                } else {
                    int k = 1;
                    while (f(improvement, g2_deviation, variance, a - (k * volatilityConstraint)) < 0) {
                        k++;
                    }
                    B = a - (k * volatilityConstraint);
                }

                double fA = f(improvement, g2_deviation, variance, A);
                double fB = f(improvement, g2_deviation, variance, B);

                while (Math.abs(B - A) > convergenceTolerance) {
                    double C = A + ((A - B) * fA / (fB - fA));
                    double fC = f(improvement, g2_deviation, variance, C);
                    if (fC * fB < 0) {
                        A = B;
                        fA = fB;
                    } else {
                        fA /= 2;
                    }
                    B = C;
                    fB = fC;
                }

                double newVolatility = Math.exp(A / 2);

                double preRatingValue = Math.sqrt(Math.pow(g2_deviation, 2) + Math.pow(newVolatility, 2));

                // Calculate new rating & deviation

                double newDeviation = 1 / Math.sqrt((1 / Math.pow(preRatingValue, 2)) + (1 / variance));

                double newRating = 0;

                for (Game game : games) {
                    Player opponent = game.getOpponent(id);
                    if (opponent == null) continue;
                    double opponentRating = (opponent.ratings.get(ratings.size() - 1) - 1500) / 173.7178;
                    double opponentDeviation = opponent.deviations.get(deviations.size() - 1) / 173.7178;
                    newRating += g(opponentDeviation) * (game.getScore(id) - E(g2_rating, opponentRating, opponentDeviation));
                }

                newRating *= Math.pow(newDeviation, 2);

                newRating += g2_rating;

                // Convert rating & deviation to Glicko scale

                newRating = newRating * 173.7178 + 1500;
                newDeviation *= 173.7178;

                // Add new calculated values to player

                ratings.add(newRating);
                deviations.add(newDeviation);
                volatilities.add(newVolatility);

            } else {

                // Rating & volatilities stay the same, deviation increases

                ratings.add(getRating());
                deviations.add(Math.sqrt(Math.pow(getDeviation(), 2) + Math.pow(getVolatility(), 2)));
                volatilities.add(getVolatility());
            }

        }

        void addRound(Game... games) {
            addRound(new ArrayList<>(List.of(games)));
        }

        // Equation implementations
        private double g(double a) {
            return 1 / Math.sqrt(1 + (3 * Math.pow(a, 2) / Math.pow(Math.PI, 2)));
        }

        private double E(double a, double b, double c) {
            return 1 / (1 + Math.exp(-g(c) * (a - b)));
        }

        private double f(double improvement, double deviation, double variance, double x) {
            double a = Math.log(Math.pow(getVolatility(), 2));
            return ((Math.exp(x) * (Math.pow(improvement, 2) - Math.pow(deviation, 2) - variance - Math.exp(x))) / (2 * Math.pow(Math.pow(deviation, 2) + variance + Math.exp(x), 2))) - ((x - a) / Math.pow(volatilityConstraint, 2));
        }

    }

    /**
     * Game object that stores both players and status of each game
     */
    static class Game {

        private UUID player1;
        private UUID player2;
        /**
         * 0: Draw
         * 1: Player1 won
         * 2: Player2 won
         */
        private int result;

        Game(UUID player1, UUID player2, int result) {
            this.player1 = player1;
            this.player2 = player2;
            this.result = result;
        }

        private double getScore(UUID player) {
            if (player == player1 || player == player2) {
                // Player was in the game
                if (result == 0) {
                    // Draw
                    return 0.5f;
                } else {
                    if (player == player1 && result == 1 || player == player2 && result == 2) {
                        // Player won
                        return 1;
                    } else {
                        // Player lost
                        return 0;
                    }
                }
            } else return -1;
        }

        /**
         * @param player ID of current player
         * @return Player object of opponent
         */
        private Player getOpponent(UUID player) {
            if (player == player1 || player == player2) {
                // Return opponent player object
                return players.get((player == player1) ? player2 : player1);
            } else return null;
        }

    }

    /**
     * Range object that represents player's expected rating range
     */
    static class Range {
        double original;
        double low;
        double high;

        Range(double original, double low, double high) {
            this.original = original;
            this.low = low;
            this.high = high;
        }

        @Override
        public String toString() {
            return round(original, 2) + " ± " + round(high - original, 2) + " ( " + round(low, 2) + " - " + round(high, 2) + " )";
        }

    }

}
