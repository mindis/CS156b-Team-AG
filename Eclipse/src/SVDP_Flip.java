import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

public class SVDP_Flip {

	public double LRATE_UF_INITIAL = 0.007;
	public double LRATE_MF_INITIAL = 0.007;

	// Tested params
	public double LRATE_MW_INITIAL = 5e-5;
	public double LRATE_UB_INITIAL = 1.563e-3;
	public double LRATE_MB_INITIAL = 2.285e-3;
	public double K_MW = 0.02;
	public double K_UB = 0.004;
	public double K_MB = 4.027e-3;
	public double K_UF = 0.01;
	public double K_MF = 0.01;
	public double INIT_BASE = 10;

	// Current lrates
	public double LRATE_UF;
	public double LRATE_MF;
	public double LRATE_MW;
	public double LRATE_UB;
	public double LRATE_MB;

	// Learning related stuff
	public double[][] userFeatures;
	public double[][] movieFeatures;
	public double[] userBias;
	public double[] movieBias;
	public double[][] uw;
	public double[][] sum_uw;
	public double[] movieRatingCount;
	public double[] norm;

	public Random rand = new Random(0);

	public static final int NUM_EPOCHS_SPAN = 5;
	public static final double MIN_ERROR_DIFF = 0.00001;

	public double GLOBAL_MEAN = 3.6033;
	public static final double CINEMATCH_BASELINE = 0.9514;

	public static final int NUM_USERS = 458293;
	public static final int NUM_MOVIES = 17770;
	public static final int NUM_DATES = 2243;
	public static final int NUM_POINTS = 102416306;
	public static final int NUM_1_POINTS = 94362233;
	public static final int NUM_2_POINTS = 1965045;
	public static final int NUM_3_POINTS = 1964391;
	public static final int NUM_4_POINTS = 1374739;
	public static final int NUM_5_POINTS = 2749898;
	public static final int NUM_TRAINING_POINTS = NUM_1_POINTS + NUM_2_POINTS
			+ NUM_3_POINTS;
	public static final int NUM_TRAINING_PROBE_POINTS = NUM_1_POINTS
			+ NUM_2_POINTS + NUM_3_POINTS + NUM_4_POINTS;

	public static final String INPUT_DATA = "all.dta";
	public static final String INPUT_INDEX = "all.idx";
	public static final String INPUT_QUAL = "qual.dta";
	public static final String LOGFILE = "log.txt";

	public int NUM_FEATURES;
	public int TEST_PARAM;

	// Data that is stored in memory
	public int[] users = new int[NUM_TRAINING_PROBE_POINTS];
	public int[] userIndex = new int[NUM_USERS];
	public short[] movies = new short[NUM_TRAINING_PROBE_POINTS];
	public short[] dates = new short[NUM_TRAINING_PROBE_POINTS];
	public byte[] ratings = new byte[NUM_TRAINING_PROBE_POINTS];
	public int[][] probeData = new int[NUM_4_POINTS][4];
	public int[][] qualData = new int[NUM_5_POINTS][3];

	public SVDP_Flip(int numFeatures) {
		this.NUM_FEATURES = numFeatures;

		// Initialize things that are specific to the training session.
		initializeVars();
	}

	public SVDP_Flip(int numFeatures, int testParam) {
		this.NUM_FEATURES = numFeatures;

		// Initialize things that are specific to the training session.
		initializeVars();
	}

	public SVDP_Flip(int numFeatures, double LRATE_BIAS, double LRATE_FEATURES,
			double LRATE_MW, double K_BIAS, double K_FEATURES, double K_MW) {
		// Set the constants to the specified values.
		this.NUM_FEATURES = numFeatures;
		this.LRATE_MW_INITIAL = LRATE_MW;

		// Initialize things that are specific to the training session.
		initializeVars();
	}

	private void initializeVars() {
		userFeatures = new double[NUM_USERS][NUM_FEATURES];
		movieFeatures = new double[NUM_MOVIES][NUM_FEATURES];
		userBias = new double[NUM_USERS];
		movieBias = new double[NUM_MOVIES];
		uw = new double[NUM_USERS][NUM_FEATURES];
		sum_uw = new double[NUM_MOVIES][NUM_FEATURES];
		movieRatingCount = new double[NUM_MOVIES];
		norm = new double[NUM_MOVIES];

		LRATE_UF = LRATE_UF_INITIAL;
		LRATE_MF = LRATE_MF_INITIAL;
		LRATE_MW = LRATE_MW_INITIAL;
		LRATE_UB = LRATE_UB_INITIAL;
		LRATE_MB = LRATE_MB_INITIAL;

		rand = new Random(0);

		// Initialize weights.
		for (int i = 0; i < userFeatures.length; i++) {
			for (int j = 0; j < userFeatures[i].length; j++) {
				userFeatures[i][j] = (rand.nextDouble() - 0.5) / 5 / INIT_BASE;
			}
		}
		for (int i = 0; i < movieFeatures.length; i++) {
			for (int j = 0; j < movieFeatures[i].length; j++) {
				movieFeatures[i][j] = (rand.nextDouble() - 0.5) / 5 / INIT_BASE;
			}
		}
	}
	
	private void setVarsToNull() {
		userFeatures = null;
		movieFeatures = null;
		userBias = null;
		movieBias = null;
		uw = null;
		sum_uw = null;
		movieRatingCount = null;
		norm = null;
	}

	public void train() throws NumberFormatException, IOException {
		System.out.println(timestampLine(String.format("Training %d features.",
				NUM_FEATURES)));

		// Read in input
		readInput();

		// Set up logfile.
		BufferedWriter logWriter = new BufferedWriter(new FileWriter(LOGFILE, true));
		logWriter.write("\n");

		// TRAIN WITH TRAINING SET ONLY (no probe)
		precompute(NUM_TRAINING_POINTS);
		double previousRmse = calcProbeRmse();
		logRmse(logWriter, previousRmse, 0);
		int numEpochsToTrain = 0;
		for (int i = 1; true; i++) {
			double rmse = trainWithNumPoints(NUM_TRAINING_POINTS);
			logRmse(logWriter, rmse, i);

			// Slow down learning rate as we're getting close to the answer.
			LRATE_UF *= .9;
			LRATE_MF *= .9;
			LRATE_MW *= .9;

			// If probe error has been going up, we should stop.
			double rmseDiff = previousRmse - rmse;
			if (rmseDiff < MIN_ERROR_DIFF) {
				System.out
						.println(timestampLine("Probe error has started"
								+ " to go up significantly; memorizing number of epochs to train."));
				generateProbeOutput();
				numEpochsToTrain = i;
				break;
			}

			previousRmse = rmse;
		}

		// TRAIN WITH PROBE.
		setVarsToNull();
		initializeVars();
		precompute(NUM_TRAINING_PROBE_POINTS);
		logEpoch(logWriter, 0);
		for (int i = 1; i <= numEpochsToTrain + NUM_EPOCHS_SPAN; i++) {
			// Train with training set AND probe.
			trainWithNumPoints(NUM_TRAINING_PROBE_POINTS);
			logEpoch(logWriter, i);

			// Slow down learning rate as we're getting close to the answer.
			LRATE_UF *= .9;
			LRATE_MF *= .9;
			LRATE_MW *= .9;

			if (i == numEpochsToTrain + NUM_EPOCHS_SPAN) {
				generateOutput();
			}
		}

		logWriter.close();

		System.out.println("Done!");
	}

	public double trainWithNumPoints(int numPoints) throws IOException {
		int user;
		short movie, date;
		byte rating;
		int prevMovie = -1;
		double err, uf, mf;
		double[] tmp_sum = new double[NUM_FEATURES];
		int u;

		for (int j = 0; j < numPoints; j++) {
			user = users[j];
			movie = movies[j];
			date = dates[j];
			rating = ratings[j];

			// Precomputation:
			if (movie != prevMovie) {
				// Pre-calc for SVD++
				for (int k = 0; k < tmp_sum.length; k++) {
					tmp_sum[k] = 0;
				}
				// Reset sum_uw and calculate sums
				for (int k = 0; k < NUM_FEATURES; k++) {
					sum_uw[movie][k] = 0;
				}
				for (int l = j; l < numPoints && movies[l] == movie; l++) {
					u = users[l];
					for (int k = 0; k < NUM_FEATURES; k++) {
						sum_uw[movie][k] += uw[u][k];
					}
				}
			}
			prevMovie = movie;

			// Calculate the error.
			err = rating - predictRating(movie, user, date);

			// Train biases.
			// User bias
			userBias[user] += LRATE_UB * (err - K_UB * userBias[user]);
			movieBias[movie] += LRATE_MB * (err - K_MB * movieBias[movie]);

			// Train all features.
			for (int k = 0; k < NUM_FEATURES; k++) {
				uf = userFeatures[user][k];
				mf = movieFeatures[movie][k];

				userFeatures[user][k] += LRATE_UF * (err * (mf + norm[movie] * sum_uw[movie][k]) - K_UF * uf);
				movieFeatures[movie][k] += LRATE_MF
						* (err * (uf) - K_MF * mf);

				// Sum uw gradients, don't train yet.
				tmp_sum[k] += err * norm[movie] * uf;
			}

			// Update movie weights if we have a new user
			if (j + 1 == numPoints || movies[j + 1] != movie) {
				for (int l = j; l >= 0 && movies[l] == movie; l--) {
					u = users[l];
					for (int k = 0; k < NUM_FEATURES; k++) {
						uw[u][k] += LRATE_MW * (tmp_sum[k] - K_MW * uw[u][k]);
					}
				}
			}
		}

		// Recalculate sum_uw
		for (int j = 0; j < NUM_MOVIES; j++) {
			for (int k = 0; k < NUM_FEATURES; k++) {
				sum_uw[j][k] = 0;
			}
		}
		for (int j = 0; j < numPoints; j++) {
			user = users[j];
			movie = movies[j];
			for (int k = 0; k < NUM_FEATURES; k++) {
				sum_uw[movie][k] += uw[user][k];
			}
		}

		// Test the model in probe set.
		return calcProbeRmse();
	}

	public void precompute(int numPoints) throws NumberFormatException,
			IOException {
		// If we are precomputing with probe, we need to re-read the data in the
		// correct order.
		if (numPoints == NUM_TRAINING_PROBE_POINTS) {
			// Read input into memory
			InputStream fis = new FileInputStream(INPUT_DATA);
			BufferedReader br = new BufferedReader(new InputStreamReader(fis,
					Charset.forName("UTF-8")));
			InputStream fisIdx = new FileInputStream(INPUT_INDEX);
			BufferedReader brIdx = new BufferedReader(new InputStreamReader(fisIdx,
					Charset.forName("UTF-8")));

			// Read INPUT_INDEX
			System.out.println(timestampLine("Loading data index..."));
			byte[] dataIndices = new byte[NUM_POINTS];
			String line;
			byte index;
			int lineNum = 0;
			while ((line = brIdx.readLine()) != null) {
				index = Byte.parseByte(line);
				dataIndices[lineNum] = index;
				lineNum++;
			}

			// Read INPUT_DATA
			System.out.println(timestampLine("Loading data..."));
			String[] parts;
			int user;
			short movie, date;
			byte rating;
			lineNum = 0;
			int trainingDataIndex = 0;
			while ((line = br.readLine()) != null) {
				parts = line.split(" ");
				user = Integer.parseInt(parts[0]) - 1;
				movie = (short) (Short.parseShort(parts[1]) - 1);
				date = (short) (Short.parseShort(parts[2]) - 1);
				rating = (byte) (Byte.parseByte(parts[3]));
				if (dataIndices[lineNum] == 1 || dataIndices[lineNum] == 2
						|| dataIndices[lineNum] == 3 || dataIndices[lineNum] == 4) {

					users[trainingDataIndex] = user;
					movies[trainingDataIndex] = movie;
					dates[trainingDataIndex] = date;
					ratings[trainingDataIndex] = rating;
					trainingDataIndex++;
				}
				lineNum++;
				if (lineNum % 10000000 == 0) {
					System.out.println(timestampLine(lineNum + " / " + NUM_POINTS));
				}
			}
		}
		// Compute mean.
		long ratingSum = 0;
		for (int i = 0; i < numPoints; i++) {
			ratingSum += ratings[i];
		}
		GLOBAL_MEAN = ((double) ratingSum) / numPoints;
		int prevMovie = -1;
		int movie;
		// Index the beginning of data for each user
		for (int i = 0; i < numPoints; i++) {
			movie = movies[i];
			if (movie != prevMovie) {
				userIndex[movie] = i;
			}
			prevMovie = movie;
		}
		// Count number of ratings for each movie
		for (int i = 0; i < numPoints; i++) {
			movie = movies[i];
			movieRatingCount[movie]++;
		}
		// Calculate norms
		for (int i = 0; i < norm.length; i++) {
			if (movieRatingCount[i] == 0) {
				norm[i] = 1;
			} else {
				norm[i] = 1 / Math.sqrt(movieRatingCount[i]);
			}
		}
		System.out.println(timestampLine("Finished precomputation.\n"));
	}

	public double predictRating(int movie, int user, int date) {
		// Compute ratings
		double ratingSum = GLOBAL_MEAN;

		// Add in biases.
		// User biases
		ratingSum += userBias[user];
		// Movie biases
		ratingSum += movieBias[movie];

		// Take dot product of feature vectors.
		for (int i = 0; i < NUM_FEATURES; i++) {
			ratingSum += (userFeatures[user][i])
					* (movieFeatures[movie][i] + sum_uw[movie][i] * norm[movie]);
		}
		return ratingSum;
	}

	public String timestampLine(String logline) {
		String currentDate = new SimpleDateFormat("h:mm:ss a").format(new Date());
		return currentDate + ": " + logline;
	}

	private void logEpoch(BufferedWriter logWriter, int i) throws IOException {
		// Print + log some stats.
		String currentDate = new SimpleDateFormat("h:mm:ss a").format(new Date());
		String logline = currentDate + String.format(": epoch %d", i);
		System.out.println(logline);
		logWriter.write(logline + "\n");
	}

	private double addAndClip(double n, double addThis) {
		n += addThis;
		if (n > 5) {
			return 5;
		} else if (n < 1) {
			return 1;
		}
		return n;
	}

	public double outputRating(int movie, int user, int date) {
		double rating = predictRating(movie, user, date);
		rating = addAndClip(rating, 0);
		return rating;
	}

	private double calcProbeRmse() throws IOException {
		int user;
		short movie, date;
		byte rating;

		// Test the model in probe set.
		double rmse = 0;
		for (int j = 0; j < probeData.length; j++) {
			user = probeData[j][0];
			movie = (short) probeData[j][1];
			date = (short) probeData[j][2];
			rating = (byte) probeData[j][3];

			rmse += Math.pow(rating - predictRating(movie, user, date), 2);
		}
		rmse = Math.sqrt(rmse / NUM_4_POINTS);

		return rmse;
	}

	private void logRmse(BufferedWriter logWriter, double rmse, int i)
			throws IOException {
		// Print + log some stats.
		double predictedPercent = (1 - rmse / CINEMATCH_BASELINE) * 100;
		String currentDate = new SimpleDateFormat("h:mm:ss a").format(new Date());
		String logline = currentDate
				+ String.format(": epoch %d probe RMSE %.5f (%.2f%%) ", i, rmse,
						predictedPercent);
		System.out.println(logline);
		logWriter.write(logline + "\n");
	}

	// Reads input with 1 2 3 data, and then appends probe onto the end.
	private void readInput() throws NumberFormatException, IOException {
		// Read input into memory
		InputStream fis = new FileInputStream(INPUT_DATA);
		BufferedReader br = new BufferedReader(new InputStreamReader(fis,
				Charset.forName("UTF-8")));
		InputStream fisIdx = new FileInputStream(INPUT_INDEX);
		BufferedReader brIdx = new BufferedReader(new InputStreamReader(fisIdx,
				Charset.forName("UTF-8")));

		// Read INPUT_INDEX
		System.out.println(timestampLine("Loading data index..."));
		byte[] dataIndices = new byte[NUM_POINTS];
		String line;
		byte index;
		int lineNum = 0;
		while ((line = brIdx.readLine()) != null) {
			index = Byte.parseByte(line);
			dataIndices[lineNum] = index;
			lineNum++;
		}

		// Read INPUT_DATA
		System.out.println(timestampLine("Loading data..."));
		String[] parts;
		int user;
		short movie, date;
		byte rating;
		lineNum = 0;
		int trainingDataIndex = 0, probeDataIndex = 0, qualDataIndex = 0;
		while ((line = br.readLine()) != null) {
			parts = line.split(" ");
			user = Integer.parseInt(parts[0]) - 1;
			movie = (short) (Short.parseShort(parts[1]) - 1);
			date = (short) (Short.parseShort(parts[2]) - 1);
			rating = (byte) (Byte.parseByte(parts[3]));
			if (dataIndices[lineNum] == 1 || dataIndices[lineNum] == 2
					|| dataIndices[lineNum] == 3) {

				users[trainingDataIndex] = user;
				movies[trainingDataIndex] = movie;
				dates[trainingDataIndex] = date;
				ratings[trainingDataIndex] = rating;
				trainingDataIndex++;
			} else if (dataIndices[lineNum] == 4) {
				probeData[probeDataIndex][0] = user;
				probeData[probeDataIndex][1] = movie;
				probeData[probeDataIndex][2] = date;
				probeData[probeDataIndex][3] = rating;

				probeDataIndex++;
			} else if (dataIndices[lineNum] == 5) {
				qualData[qualDataIndex][0] = user;
				qualData[qualDataIndex][1] = movie;
				qualData[qualDataIndex][2] = date;

				qualDataIndex++;
			}
			lineNum++;
			if (lineNum % 10000000 == 0) {
				System.out.println(timestampLine(lineNum + " / " + NUM_POINTS));
			}
		}

		// Now add probe data onto the end of the four arrays.
		for (int i = 0; i < probeData.length; i++) {
			user = probeData[i][0];
			movie = (short) probeData[i][1];
			date = (short) probeData[i][2];
			rating = (byte) probeData[i][3];

			users[trainingDataIndex] = user;
			movies[trainingDataIndex] = movie;
			dates[trainingDataIndex] = date;
			ratings[trainingDataIndex] = rating;
			trainingDataIndex++;
		}

		System.out.println(timestampLine("Done loading data."));
	}

	private void generateOutput() throws IOException {
		FileWriter fstream = new FileWriter("svdp_flip_1234_with_probe_training");
		BufferedWriter out = new BufferedWriter(fstream);
		int movie, user, date;
		double predictedRating;
		for (int i = 0; i < NUM_TRAINING_PROBE_POINTS; i++) {
			user = users[i];
			movie = movies[i];
			date = dates[i];

			predictedRating = outputRating(movie, user, date);
			out.write(String.format("%.4f\n", predictedRating));
		}
		out.close();
		
		fstream = new FileWriter("svdp_flip_5_with_probe_training");
		out = new BufferedWriter(fstream);
		for (int i = 0; i < qualData.length; i++) {
			user = qualData[i][0];
			movie = qualData[i][1];
			date = qualData[i][2];

			predictedRating = outputRating(movie, user, date);
			out.write(String.format("%.4f\n", predictedRating));
		}
		out.close();
	}

	private void generateProbeOutput() throws IOException {
		FileWriter fstream = new FileWriter("svdp_flip_123_no_probe_training");
		BufferedWriter out = new BufferedWriter(fstream);
		int user;
		short movie, date;
		double predictedRating;
		for (int j = 0; j < NUM_TRAINING_POINTS; j++) {
			user = users[j];
			movie = (short) movies[j];
			date = (short) dates[j];

			predictedRating = outputRating(movie, user, date);
			out.write(String.format("%.4f\n", predictedRating));
		}
		out.close();
		
		fstream = new FileWriter("svdp_flip_4_no_probe_training");
		out = new BufferedWriter(fstream);
		// Test the model in probe set.
		for (int j = 0; j < probeData.length; j++) {
			user = probeData[j][0];
			movie = (short) probeData[j][1];
			date = (short) probeData[j][2];

			predictedRating = outputRating(movie, user, date);
			out.write(String.format("%.4f\n", predictedRating));
		}
		out.close();
	}

	public static void main(String[] args) throws NumberFormatException,
			IOException {

		SVDP_Flip trainer;
		if (args.length == 1) {
			trainer = new SVDP_Flip(Integer.parseInt(args[0]));
		} else if (args.length == 2) {
			trainer = new SVDP_Flip(Integer.parseInt(args[0]), Integer.parseInt(args[1]));
		} else if (args.length == 7) {
			trainer = new SVDP_Flip(Integer.parseInt(args[0]),
					Double.parseDouble(args[1]), Double.parseDouble(args[2]),
					Double.parseDouble(args[3]), Double.parseDouble(args[4]),
					Double.parseDouble(args[5]), Double.parseDouble(args[6]));
		} else {
			System.exit(1);
			return;
		}
		trainer.train();
	}
}
