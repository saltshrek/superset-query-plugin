const path = require("path");
const { ModuleFederationPlugin } = require("webpack").container;
const packageConfig = require("./package");
const extensionConfig = require("../extension.json");

// Superset loads the extension as a Module Federation remote. The container
// name must match what superset-extensions puts in the manifest:
// camelCase(publisher) + "_" + camelCase(name).
const federationName = "quillfern_sqlExamples";

module.exports = (env, argv) => ({
  // superset-extensions always runs `npm run build` (production mode); the
  // remote's exposed module is the only entry.
  entry: {},
  mode: argv.mode === "production" ? "production" : "development",
  output: {
    clean: true,
    chunkFilename: "[name].[contenthash].js",
    path: path.resolve(__dirname, "dist"),
    publicPath: `/api/v1/extensions/${extensionConfig.publisher}/${extensionConfig.name}/`,
  },
  resolve: {
    extensions: [".ts", ".tsx", ".js", ".jsx"],
  },
  externalsType: "window",
  externals: {
    "@apache-superset/core": "superset",
  },
  module: {
    rules: [{ test: /\.tsx?$/, use: "ts-loader", exclude: /node_modules/ }],
  },
  plugins: [
    new ModuleFederationPlugin({
      name: federationName,
      filename: "remoteEntry.[contenthash].js",
      exposes: {
        "./index": "./src/index.tsx",
      },
      // Use the host's React rather than bundling a second copy.
      shared: {
        react: {
          singleton: true,
          requiredVersion: packageConfig.peerDependencies.react,
          import: false,
        },
        "react-dom": {
          singleton: true,
          requiredVersion: packageConfig.peerDependencies["react-dom"],
          import: false,
        },
        // Superset's own Tree, Button etc. are antd's, and the host shares
        // its copy, so ours pick up Superset's theme.
        antd: {
          singleton: true,
          requiredVersion: packageConfig.peerDependencies.antd,
          import: false,
        },
      },
    }),
  ],
});
