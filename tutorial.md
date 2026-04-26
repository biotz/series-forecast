# series-forecast tutorial

This guide turns the current `series-forecast` scaffold into a first recurrent model baseline. The repository already has:

- a DuckDB/JDBC data path in `src/series_forecast/series_forecast.clj`
- a published `clojure-ml` dependency with `:gru` and `:lstm` support
- `honey.sql` and `next.jdbc` for querying and shaping the data

The recommended first model is a GRU. It is smaller than an LSTM, trains faster, and is usually the best first recurrent baseline for a time-series project like this one.

## 1. Understand the data

The Kaggle store sales dataset has several CSVs:

- `train.csv`
- `test.csv`
- `stores.csv`
- `oil.csv`
- `holidays_events.csv`
- `transactions.csv`

The first job is not modeling. It is building a clean, chronological table with one row per date/store/item series and the target you want to predict.

Start by answering these questions:

1. What is the target?
2. What is the forecasting horizon?
3. What time features are available before the forecast date?
4. What external signals matter, such as oil price or holidays?

For the Kaggle problem, the usual target is `sales`, forecast one or more future days ahead.

## 2. Load the raw files

The repo already knows how to download the dataset and connect to DuckDB.

The current entry point in `src/series_forecast/series_forecast.clj` has:

- `download-dataset!`
- `connect!`
- `query-stores!`

Use those pieces as the starting point, but do not train from the raw CSVs directly. First land them in a structured table or a reproducible query pipeline.

Suggested workflow:

1. Download the zip archive.
2. Unpack the CSVs.
3. Load them into DuckDB.
4. Write SQL queries that join `train`, `stores`, `oil`, and `holidays_events`.

## 3. Build one training table

Create a single table or view with the features you want to feed the model.

At minimum, include:

- `date`
- `store_nbr`
- `family` or the equivalent series identifier
- `sales` as the target
- calendar features such as day-of-week, month, and holiday flags
- external regressors such as oil price

Keep the table ordered by time. The model should never see future rows during feature construction.

Practical rule:

- everything used for a row at time `t` must be known at or before `t`
- do not leak future sales into the input window

## 4. Choose the prediction shape

You need to decide what each training example looks like.

For a GRU, the common shape is:

- input: `window_size x feature_count`
- output: next-step sales, or the next `horizon` values

Example:

- input window: the last 30 days
- features: sales history, oil price, day-of-week, holiday flags, store metadata
- target: sales on day 31

If you want multi-step forecasting, start with a one-step model first. It is easier to debug.

## 4.1 Embeddings are inside the features, not a replacement for time

If you want learned vector embeddings for features, that usually changes how a
single row is represented. It does not remove the need for a time window.

Think of the model input in two parts:

1. feature embedding: how one day or one row becomes a dense vector
2. sequence window: how many past days the recurrent model sees

For example, if you have 33 raw features, you might map them to a 33-dim
embedding vector per timestep. Then each timestep becomes one vector, and the
GRU reads a stack of those timestep vectors over time.

That means the shape is still sequence-shaped:

- one sample: `window_size x embedding_dim`
- one batch with `batch-first? true`: `batch x window_size x embedding_dim`

The embedding layer changes `feature_count` into `embedding_dim`. It does not
replace the window.

If you only have one row of features and no time history, a GRU or LSTM is
usually the wrong model. In that case, use a feed-forward model instead.

The `33x33` idea is useful only if you have a specific meaning for that matrix.
Common meanings are:

- a 33-row repeated feature matrix
- pairwise feature interactions
- an embedding table lookup per categorical feature

That is a separate design choice from the recurrent time window.

## 5. Convert rows into sequences

This is the main modeling step.

For each prediction point:

1. take the previous `window_size` rows
2. pack them into one sequence tensor
3. pair that sequence with the future target value

You will end up with:

- `X`: a batch of sequences
- `y`: the aligned target values

The important part is consistency. Every sample must have the same sequence length and feature count.

Typical preprocessing steps:

- fill missing values
- normalize continuous features
- one-hot encode categorical features, or embed them later
- sort strictly by time before windowing

## 6. Split by time, not by row

Do not use a random train/test split for time series.

Use a chronological split:

1. earliest segment for training
2. middle segment for validation
3. latest segment for final evaluation

This avoids leakage and gives a realistic error estimate.

If you are predicting future sales, the validation set should represent the most recent dates available in the training data.

## 7. Start with a GRU

The `clojure-ml` library already exposes recurrent blocks, including `:gru` and `:lstm`.

Use GRU first because:

- it is simpler
- it is faster to train
- it is usually easier to tune

Your first model can be very small:

- one GRU layer
- a modest hidden size
- a linear projection to the output

Do not start with a deep stack. Get one model working end to end first.

For this dataset, a reasonable first pass is:

1. build a per-day feature vector
2. optionally embed categorical features into dense vectors
3. stack the last `window_size` days into one sequence
4. feed that sequence into GRU with `:batch-first? true`

In other words, the window is the temporal context. The embeddings are the
per-step representation inside that context.

## 8. Sketch the model spec

The exact wiring depends on the `clojure-ml` API you choose, but the structure should look like this:

1. input sequence
2. GRU recurrent layer
3. optional dropout
4. linear output head

If you are using the spec-driven API from `clojure-ml`, the idea is roughly:

```clojure
{:type :sequential
 :children
 [{:type :gru :state-size 64 :num-layers 1 :batch-first? true}
  {:type :linear :units 1}]}
```

That is only a shape of the idea, not a final model. You will still need to adapt it to the actual data layout and output shape you want.

## 9. Prepare the training loop

Once the data and model are defined, wire the loop:

1. build mini-batches
2. forward pass
3. compute loss
4. backpropagate
5. update parameters
6. evaluate on validation data

Start with a small number of epochs and a small batch size. You want a working loop before you want a good score.

Useful first loss choices:

- mean squared error for raw sales regression
- mean absolute error if you want less sensitivity to spikes

## 10. Measure a baseline first

Before trying a neural net, build a trivial baseline:

- yesterday’s sales
- moving average over the last `n` days
- seasonal average by weekday or month

This gives you a sanity check. If the GRU cannot beat a simple baseline, the issue is usually the data pipeline, not the model depth.

## 11. Add a simple evaluation report

Track at least:

- training loss
- validation loss
- MAE or RMSE on the validation window

If you are forecasting multiple product families or stores, also inspect performance by segment. A model that averages well overall may still fail badly on specific stores.

## 12. Iterate in this order

When the first version runs, improve it in this order:

1. fix data leakage
2. improve windowing and scaling
3. tune GRU hidden size and sequence length
4. try LSTM only if GRU underfits
5. add more useful regressors
6. only then consider a deeper architecture

## 13. Suggested repo work plan

The current repository is still mostly a data scaffold. A practical implementation order is:

1. replace the placeholder `-main` in `src/series_forecast/series_forecast.clj` with a data preparation pipeline
2. add a feature-window builder namespace
3. add a training namespace that builds a GRU model through `clojure-ml`
4. add a validation script that reports a baseline metric
5. add tests for the windowing and split logic

## 14. What to expect from the first pass

The first GRU model will probably not be the final answer.

That is normal. The first job is to prove that:

- the data pipeline is correct
- the sequence shapes are correct
- the model trains without leaking future data
- the evaluation is stable

Once that works, you can compare GRU against LSTM and decide whether the extra complexity is worth it.
