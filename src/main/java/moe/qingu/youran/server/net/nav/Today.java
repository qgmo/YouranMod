package moe.qingu.youran.server.net.nav;

/** 侧栏“今日”：游戏内日期时间、天气、在线人数（主线程每秒拍一次，网络线程读）。不可变 */
final class Today {
    static final Today UNKNOWN = new Today(1, 6, 0, Weather.CLEAR, 0, 0);

    enum Weather {
        CLEAR("晴"), RAIN("雨"), THUNDER("雷雨");

        final String label;

        Weather(String label) {
            this.label = label;
        }
    }

    /** 第几天（从 1 起） */
    final long day;
    final int hour;
    final int minute;
    final Weather weather;
    final int online;
    final int maxPlayers;

    Today(long day, int hour, int minute, Weather weather, int online, int maxPlayers) {
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.weather = weather;
        this.online = online;
        this.maxPlayers = maxPlayers;
    }

    /** 按主世界的时间（{@code World#getWorldTime}，0 是早上 6 点）算 */
    static Today of(long worldTime, Weather weather, int online, int maxPlayers) {
        long t = Math.max(0, worldTime);
        long day = t / 24000 + 1;
        int inDay = (int) (t % 24000);
        int hour = (inDay / 1000 + 6) % 24;
        int minute = (inDay % 1000) * 60 / 1000;
        return new Today(day, hour, minute, weather, online, maxPlayers);
    }

    String clock() {
        return (hour < 10 ? "0" : "") + hour + ":" + (minute < 10 ? "0" : "") + minute;
    }

    /** 凌晨、早上、上午、中午、下午、傍晚、晚上 */
    String period() {
        if (hour < 5) {
            return "凌晨";
        }
        if (hour < 8) {
            return "早上";
        }
        if (hour < 11) {
            return "上午";
        }
        if (hour < 13) {
            return "中午";
        }
        if (hour < 17) {
            return "下午";
        }
        if (hour < 19) {
            return "傍晚";
        }
        return "晚上";
    }
}
