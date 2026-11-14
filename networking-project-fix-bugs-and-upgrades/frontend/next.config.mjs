const config = {
  output: "export",
  trailingSlash: true,
  images: { unoptimized: true },
  ...(process.env.NODE_ENV === "development"
    ? {
        async rewrites() {
          return [
            {
              source: "/api/:path*",
              destination: "http://127.0.0.1:6080/api/:path*",
            },
          ];
        },
      }
    : {}),
};
export default config;
