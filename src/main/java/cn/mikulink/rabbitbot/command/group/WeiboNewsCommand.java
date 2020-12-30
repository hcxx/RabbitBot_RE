package cn.mikulink.rabbitbot.command.group;

import cn.mikulink.rabbitbot.command.GroupCommand;
import cn.mikulink.rabbitbot.constant.ConstantCommon;
import cn.mikulink.rabbitbot.constant.ConstantFile;
import cn.mikulink.rabbitbot.constant.ConstantWeiboNews;
import cn.mikulink.rabbitbot.entity.CommandProperties;
import cn.mikulink.rabbitbot.entity.apirequest.weibo.InfoStatuses;
import cn.mikulink.rabbitbot.entity.apirequest.weibo.InfoWeiboHomeTimeline;
import cn.mikulink.rabbitbot.filemanage.FileManagerConfig;
import cn.mikulink.rabbitbot.service.RabbitBotService;
import cn.mikulink.rabbitbot.service.WeiboNewsService;
import cn.mikulink.rabbitbot.sys.annotate.Command;
import cn.mikulink.rabbitbot.utils.NumberUtil;
import cn.mikulink.rabbitbot.utils.RandomUtil;
import cn.mikulink.rabbitbot.utils.StringUtil;
import com.alibaba.fastjson.JSONObject;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.Member;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.PlainText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * @author MikuLink
 * @date 2020/8/31 10:50
 * for the Reisen
 * <p>
 * 微博消息相关
 */
@Command
public class WeiboNewsCommand implements GroupCommand {
    private static final Logger logger = LoggerFactory.getLogger(WeiboNewsCommand.class);

    @Autowired
    private RabbitBotService rabbitBotService;
    @Autowired
    private WeiboNewsService weiboNewsService;

    @Override
    public CommandProperties properties() {
        return new CommandProperties("WeiboNews", "wbn");
    }

    @Override
    public Message execute(Member sender, ArrayList<String> args, MessageChain messageChain, Group subject) {
        //权限限制
        if (!rabbitBotService.isMaster(sender.getId())) {
            return new PlainText(RandomUtil.rollStrFromList(ConstantCommon.COMMAND_MASTER_ONLY));
        }

        if (null == args || args.size() == 0) {
            return new PlainText("[.wbn (on,off,lasttag,refreshSinceId,token,exec)]");
        }

        //二级指令
        String arg = args.get(0);
        switch (arg) {
            case ConstantWeiboNews.ON:
                //开启微博消息推送
                //修改配置
                ConstantCommon.common_config.put("weiboNewStatus", "1");
                //更新配置文件
                FileManagerConfig.doCommand(ConstantFile.FILE_COMMAND_WRITE);
                return new PlainText(ConstantWeiboNews.OPEN_SUCCESS);
            case ConstantWeiboNews.OFF:
                //关闭微博消息推送
                //修改配置
                ConstantCommon.common_config.put("weiboNewStatus", "0");
                //更新配置文件
                FileManagerConfig.doCommand(ConstantFile.FILE_COMMAND_WRITE);
                return new PlainText(ConstantWeiboNews.OFF_SUCCESS);
            case ConstantWeiboNews.SINCEID:
                //从外部接受sinceId
                if (args.size() < 2 || StringUtil.isEmpty(args.get(1))) {
                    return new PlainText(ConstantWeiboNews.SINCEID_OVERRIDE_FAIL);
                }
                String sinceIdStr = args.get(1);
                if (!NumberUtil.isNumberOnly(sinceIdStr)) {
                    return new PlainText(ConstantWeiboNews.SINCEID_OVERRIDE_FAIL_NOW_NUMBER);
                }
                //覆写SINCEID配置
                ConstantCommon.common_config.put("sinceId", sinceIdStr);
                //更新配置文件
                FileManagerConfig.doCommand(ConstantFile.FILE_COMMAND_WRITE);
                return new PlainText(ConstantWeiboNews.SINCEID_OVERRIDE_SUCCESS);
            case ConstantWeiboNews.SINCEID_REFRESH:
                //刷新sinceId
                weiboNewsService.refreshSinceId();
                return new PlainText(ConstantWeiboNews.SINCEID_OVERRIDE_SUCCESS);
            case ConstantWeiboNews.EXEC:
                //立刻执行一次推送
                doWeiboPush(subject);
                break;
        }
        return null;
    }

    //在当前群执行一次微博消息推送，此操作会覆盖全局的最后推送时间，一般用于补推
    private void doWeiboPush(Group subject) {
        try {
            //获取资讯
            InfoWeiboHomeTimeline weiboNews = weiboNewsService.getWeiboNews(10);
            Long sinceId = weiboNews.getSince_id();
            //刷新最后推文标识，但如果一次请求中没有获取到新数据，since_id会为0
            if (0 != sinceId) {
                logger.info(String.format("微博sinceId刷新：[%s]->[%s]", ConstantCommon.common_config.get("sinceId"), sinceId));
                //刷新sinceId配置
                ConstantCommon.common_config.put("sinceId", String.valueOf(sinceId));
                //更新配置文件
                FileManagerConfig.doCommand(ConstantFile.FILE_COMMAND_WRITE);
            }
            List<InfoStatuses> statusesList = weiboNews.getStatuses();
            if (null == statusesList || statusesList.size() <= 0) {
                return;
            }

            //在当前群推一遍咨询
            for (InfoStatuses info : statusesList) {
                //解析微博报文
                MessageChain msgChain = weiboNewsService.parseWeiboBody(info);
                if (null != info.getRetweeted_status()) {
                    //追加被转发的微博消息
                    msgChain = msgChain.plus(weiboNewsService.parseWeiboBody(info.getRetweeted_status(), true));
                }
                //发送微博信息
                subject.sendMessage(msgChain);
                //每次发送之间间隔一点时间，免得瞬间刷屏太厉害
                Thread.sleep(1000L * 5);
            }

        } catch (Exception ex) {
            logger.error("主动微博消息推送执行异常", ex);
            subject.sendMessage(ConstantWeiboNews.EXEC_ERROR);
        }
    }
}
