// Adapted from bloc97 Anime4K v0.9; MIT. See LICENSE-Anime4K.txt and NOTICE.md.
precision highp float;
varying vec2 vTex;
uniform sampler2D uTexture;
uniform sampler2D uAux;
uniform sampler2D uOriginal;
uniform vec2 uPt;
uniform float uStrength;



vec4 getLargest(vec4 cc, vec4 lightestColor, vec4 a, vec4 b, vec4 c) {
	vec4 newColor = cc * (1.0 - uStrength) + ((a + b + c) / 3.0) * uStrength;
	if (newColor.a > lightestColor.a) {
		return newColor;
	}
	return lightestColor;
}

vec4 getRGBL(vec2 pos) {
    return texture2D(uTexture, pos);
}

float min3v(vec4 a, vec4 b, vec4 c) {
	return min(min(a.a, b.a), c.a);
}
float max3v(vec4 a, vec4 b, vec4 c) {
	return max(max(a.a, b.a), c.a);
}


vec4 reconstruct()  {
	vec2 d = uPt;
	
    vec4 cc = getRGBL(vTex);
	vec4 t = getRGBL(vTex + vec2(0.0, -d.y));
	vec4 tl = getRGBL(vTex + vec2(-d.x, -d.y));
	vec4 tr = getRGBL(vTex + vec2(d.x, -d.y));
	
	vec4 l = getRGBL(vTex + vec2(-d.x, 0.0));
	vec4 r = getRGBL(vTex + vec2(d.x, 0.0));
	
	vec4 b = getRGBL(vTex + vec2(0.0, d.y));
	vec4 bl = getRGBL(vTex + vec2(-d.x, d.y));
	vec4 br = getRGBL(vTex + vec2(d.x, d.y));
	
	vec4 lightestColor = cc;

	//Kernel 0.0 and 4.0
	float maxDark = max3v(br, b, bl);
	float minLight = min3v(tl, t, tr);
	
	if (minLight > cc.a && minLight > maxDark) {
		lightestColor = getLargest(cc, lightestColor, tl, t, tr);
	} else {
		maxDark = max3v(tl, t, tr);
		minLight = min3v(br, b, bl);
		if (minLight > cc.a && minLight > maxDark) {
			lightestColor = getLargest(cc, lightestColor, br, b, bl);
		}
	}
	
	//Kernel 1.0 and 5.0
	maxDark = max3v(cc, l, b);
	minLight = min3v(r, t, tr);
	
	if (minLight > maxDark) {
		lightestColor = getLargest(cc, lightestColor, r, t, tr);
	} else {
		maxDark = max3v(cc, r, t);
		minLight = min3v(bl, l, b);
		if (minLight > maxDark) {
			lightestColor = getLargest(cc, lightestColor, bl, l, b);
		}
	}
	
	//Kernel 2.0 and 6.0
	maxDark = max3v(l, tl, bl);
	minLight = min3v(r, br, tr);
	
	if (minLight > cc.a && minLight > maxDark) {
		lightestColor = getLargest(cc, lightestColor, r, br, tr);
	} else {
		maxDark = max3v(r, br, tr);
		minLight = min3v(l, tl, bl);
		if (minLight > cc.a && minLight > maxDark) {
			lightestColor = getLargest(cc, lightestColor, l, tl, bl);
		}
	}
	
	//Kernel 3.0 and 7.0
	maxDark = max3v(cc, l, t);
	minLight = min3v(r, br, b);
	
	if (minLight > maxDark) {
		lightestColor = getLargest(cc, lightestColor, r, br, b);
	} else {
		maxDark = max3v(cc, r, b);
		minLight = min3v(t, l, tl);
		if (minLight > maxDark) {
			lightestColor = getLargest(cc, lightestColor, t, l, tl);
		}
	}
	
	
	return lightestColor;
}


void main(){gl_FragColor=reconstruct();}
